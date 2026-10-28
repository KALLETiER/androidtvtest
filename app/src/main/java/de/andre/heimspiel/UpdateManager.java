package de.andre.heimspiel;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

/** User-triggered update checks, verified APK staging and the system installation dialog. */
public final class UpdateManager implements AutoCloseable {
    public static final int PICK_APK = 72;
    public static final int INSTALL_SETTINGS = 73;
    public static final int INSTALL_APK = 74;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final int TIMEOUT_MS = 15000;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    // Private GitHub repository: use APK files unless a public source is explicitly configured.
    private static final String DEFAULT_SOURCE = "";

    private final Activity activity;
    private final Consumer<JSONObject> callback;
    private final Runnable beforeExternal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences preferences;
    private final Object lock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final String packageName;
    private final String installedVersionName;
    private final long installedVersionCode;
    private final File directory;

    private volatile boolean closed;
    private volatile HttpsURLConnection activeConnection;
    private String source;
    private String status = "idle";
    private String message = "Wähle eine APK oder hinterlege eine HTTPS-Updatequelle.";
    private boolean busy;
    private int progress = -1;
    private Release available;
    private File readyApk;
    private long readyVersionCode;
    private String readyVersionName = "";
    private boolean pickerOpen;

    public UpdateManager(Activity activity, Consumer<JSONObject> callback, Runnable beforeExternal) {
        this.activity = activity;
        this.callback = callback;
        this.beforeExternal = beforeExternal;
        packageName = activity.getPackageName();
        preferences = activity.getSharedPreferences("heimspiel_updates", Activity.MODE_PRIVATE);
        source = preferences.getString("source", DEFAULT_SOURCE);
        if (source == null) source = "";
        try { if (!source.isEmpty()) httpsUrl(source); }
        catch (IOException invalid) { source = ""; }
        long code = 0;
        String name = "";
        try {
            PackageInfo current = currentPackage();
            code = versionCode(current);
            name = current.versionName == null ? "" : current.versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }
        installedVersionCode = code;
        installedVersionName = name;
        directory = new File(activity.getCacheDir(), "updates");
        if (!source.isEmpty()) message = "Updatequelle gespeichert. Mit Prüfen nach Updates suchen.";
    }

    /** Safe to call from the WebView bridge thread. Returned objects are independent snapshots. */
    public JSONObject getInfoJSON() {
        synchronized (lock) {
            JSONObject json = new JSONObject();
            boolean ready = readyApk != null && readyApk.isFile();
            boolean allowed = installAllowed();
            try {
                json.put("installedVersionName", installedVersionName);
                json.put("installedVersionCode", installedVersionCode);
                json.put("source", source);
                json.put("status", status);
                json.put("message", message);
                json.put("busy", busy);
                json.put("progress", progress);
                json.put("versionAvailable", available != null);
                json.put("versionCode", available == null ? 0 : available.versionCode);
                json.put("versionName", available == null ? "" : available.versionName);
                json.put("notes", available == null ? "" : available.notes);
                json.put("ready", ready);
                json.put("readyVersionCode", ready ? readyVersionCode : 0);
                json.put("readyVersionName", ready ? readyVersionName : "");
                json.put("installAllowed", allowed);
                json.put("canDownload", available != null && !busy);
                json.put("canInstall", ready && allowed && !busy);
            } catch (JSONException ignored) { }
            return json;
        }
    }

    /** Empty input disables online checks. Invalid input never replaces the saved source. */
    public boolean setSource(String value) {
        if (closed) return false;
        String next = value == null ? "" : value.trim();
        try {
            if (next.length() > 2048) throw new IOException("Die Updateadresse ist zu lang.");
            if (!next.isEmpty()) httpsUrl(next);
        } catch (IOException invalid) {
            synchronized (lock) {
                if (!busy) setState("error", invalid.getMessage(), false, -1);
            }
            emit();
            return false;
        }
        synchronized (lock) {
            if (closed || busy) return false;
            source = next;
            available = null;
            preferences.edit().putString("source", source).apply();
            setState(readyApk == null ? "idle" : "ready", next.isEmpty()
                    ? "Online-Updatequelle entfernt. Eine APK kann weiterhin ausgewählt werden."
                    : "Updatequelle gespeichert. Mit Prüfen nach Updates suchen.", false, -1);
        }
        emit();
        return true;
    }

    public void check() {
        final String address;
        final long token;
        synchronized (lock) {
            if (closed || busy) return;
            if (source.isEmpty()) {
                setState("error", "Bitte zuerst die HTTPS-Adresse einer update.json speichern.", false, -1);
                emit();
                return;
            }
            address = source;
            token = begin("checking", "Suche nach einer neuen Version …");
        }
        emit();
        submit(token, () -> {
            HttpsURLConnection connection = null;
            try {
                connection = connect(httpsUrl(address), "application/json", token);
                long length = connection.getContentLengthLong();
                if (length > MAX_MANIFEST_BYTES) throw new IOException("Die Updatebeschreibung ist zu groß.");
                byte[] bytes;
                try (InputStream in = connection.getInputStream()) {
                    bytes = readSmall(in, MAX_MANIFEST_BYTES, token);
                }
                Release release = parseRelease(new String(bytes, StandardCharsets.UTF_8));
                synchronized (lock) {
                    if (!isCurrent(token)) return;
                    available = release.versionCode > installedVersionCode ? release : null;
                    setState(available == null ? "current" : "available", available == null
                            ? "Deine installierte Version ist aktuell."
                            : "Version " + release.versionName + " ist verfügbar.", false, -1);
                }
                emit();
            } catch (Exception failure) { fail(token, failure); }
            finally { disconnect(connection); }
        });
    }

    public void download() {
        final Release release;
        final long token;
        synchronized (lock) {
            if (closed || busy) return;
            if (available == null) {
                setState("error", "Bitte zuerst nach einer neuen Version suchen.", false, -1);
                emit();
                return;
            }
            release = available;
            token = begin("downloading", "Update wird heruntergeladen …");
        }
        emit();
        submit(token, () -> {
            HttpsURLConnection connection = null;
            File temporary = null;
            try {
                ensureDirectory();
                connection = connect(httpsUrl(release.apkUrl), APK_MIME, token);
                long length = connection.getContentLengthLong();
                if (length > MAX_APK_BYTES) throw new IOException("Die APK ist größer als 64 MB.");
                temporary = File.createTempFile("download-", ".part", directory);
                MessageDigest digest = sha256();
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temporary)) {
                    copyBounded(in, out, length, digest, token);
                }
                if (!hex(digest.digest()).equals(release.sha256)) throw new IOException("Prüfsumme stimmt nicht überein. Die APK wurde verworfen.");
                VerifiedApk verified = verifyApk(temporary);
                if (verified.versionCode != release.versionCode || !verified.versionName.equals(release.versionName)) {
                    throw new IOException("Die APK-Version passt nicht zur Updatebeschreibung.");
                }
                stage(temporary, verified, token);
                temporary = null;
            } catch (Exception failure) { fail(token, failure); }
            finally {
                disconnect(connection);
                if (temporary != null) temporary.delete();
            }
        });
    }

    public void chooseApk() {
        onMain(() -> {
            synchronized (lock) {
                if (closed || busy) return;
                begin("importing", "Wähle die neue Heimspiel-APK aus.");
                pickerOpen = true;
            }
            emit();
            Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                beforeExternal.run();
                activity.startActivityForResult(choose, PICK_APK);
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                synchronized (lock) {
                    pickerOpen = false;
                    setState("error", "Auf diesem Fernseher ist keine APK-Dateiauswahl verfügbar. Nutze eine HTTPS-Updatequelle oder die Installation über WLAN.", false, -1);
                }
                emit();
            }
        });
    }

    /** Rechecks the private staged APK and then asks Android to show its normal installer. */
    public void install() {
        final File apk;
        final long token;
        synchronized (lock) {
            if (closed || busy) return;
            if (readyApk == null || !readyApk.isFile()) {
                setState("error", "Bitte zuerst eine neue APK herunterladen oder auswählen.", false, -1);
                emit();
                return;
            }
            apk = readyApk;
            token = begin("installing", "APK wird vor der Installation erneut geprüft …");
        }
        emit();
        submit(token, () -> {
            try {
                verifyApk(apk);
                onMain(() -> showInstaller(apk, token));
            } catch (Exception failure) { fail(token, failure); }
        });
    }

    public void openInstallSettings() {
        onMain(() -> {
            synchronized (lock) { if (closed || busy) return; }
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + packageName));
            try {
                beforeExternal.run();
                activity.startActivityForResult(settings, INSTALL_SETTINGS);
                permissionMessage();
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                try {
                    beforeExternal.run();
                    activity.startActivityForResult(new Intent(Settings.ACTION_SETTINGS), INSTALL_SETTINGS);
                    synchronized (lock) {
                        setState("needs_permission", "Erlaube in den TV-Einstellungen die Installation unbekannter Apps für Andrés Heimspiel. Kehre zurück und wähle danach Installieren.", false, -1);
                    }
                    emit();
                } catch (ActivityNotFoundException | SecurityException missing) {
                    synchronized (lock) {
                        setState("error", "Die Installationsfreigabe lässt sich hier nicht öffnen. Öffne sie über die Entwickleroptionen des Fernsehers.", false, -1);
                    }
                    emit();
                }
            }
        });
    }

    /** Must run before MainActivity handles its other picker results. No installation on return. */
    public boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_APK && request != INSTALL_SETTINGS && request != INSTALL_APK) return false;
        if (closed) return true;
        if (request == INSTALL_SETTINGS) {
            synchronized (lock) {
                setState(installAllowed() ? (readyApk == null ? "idle" : "ready") : "needs_permission",
                        installAllowed() ? "Installation erlaubt. Wähle Installieren, um den Systemdialog zu öffnen."
                                : "Die Installationsfreigabe fehlt noch. Du kannst die APK weiterhin über WLAN installieren.", false, -1);
            }
            emit();
            return true;
        }
        if (request == INSTALL_APK) {
            synchronized (lock) {
                setState(readyApk == null ? "idle" : "ready",
                        "Installationsdialog geschlossen. Falls das Update nicht installiert wurde, kannst du es erneut starten.", false, -1);
            }
            emit();
            return true;
        }
        final long token;
        synchronized (lock) {
            if (!pickerOpen) return true;
            pickerOpen = false;
            token = generation.get();
            if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
                setState(readyApk == null ? "idle" : "ready", "Dateiauswahl abgebrochen.", false, -1);
                emit();
                return true;
            }
            setState("importing", "APK wird eingelesen und geprüft …", true, -1);
        }
        final Uri uri = data.getData();
        emit();
        submit(token, () -> importApk(uri, token));
        return true;
    }

    @Override public void close() {
        synchronized (lock) {
            closed = true;
            generation.incrementAndGet();
            busy = false;
        }
        HttpsURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        worker.shutdownNow();
    }

    private void importApk(Uri uri, long token) {
        File temporary = null;
        try {
            if (!"content".equalsIgnoreCase(uri.getScheme())) throw new IOException("Bitte eine APK aus der Dateiauswahl verwenden.");
            ensureDirectory();
            long length = -1;
            try (Cursor cursor = activity.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) length = cursor.getLong(0);
            } catch (RuntimeException ignored) { /* Some document providers do not expose size. */ }
            if (length > MAX_APK_BYTES) throw new IOException("Die APK ist größer als 64 MB.");
            temporary = File.createTempFile("import-", ".part", directory);
            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new IOException("Die ausgewählte APK ist nicht lesbar.");
                copyBounded(in, out, length, null, token);
            }
            VerifiedApk verified = verifyApk(temporary);
            stage(temporary, verified, token);
            temporary = null;
        } catch (Exception failure) { fail(token, failure); }
        finally { if (temporary != null) temporary.delete(); }
    }

    private void stage(File temporary, VerifiedApk verified, long token) throws IOException {
        synchronized (lock) {
            requireCurrent(token);
            File destination = new File(directory, "heimspiel-update.apk");
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            readyApk = destination;
            readyVersionCode = verified.versionCode;
            readyVersionName = verified.versionName;
            setState("ready", "Version " + readyVersionName + " ist geprüft und bereit. Die Installation startet erst mit deiner Bestätigung im Systemdialog.", false, 100);
        }
        emit();
    }

    private void showInstaller(File apk, long token) {
        synchronized (lock) {
            if (!isCurrent(token)) return;
            if (!installAllowed()) {
                setState("needs_permission", "Erlaube zuerst die Installation unbekannter Apps für Andrés Heimspiel. Wähle danach erneut Installieren.", false, -1);
                emit();
                return;
            }
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, packageName + ".updates", apk);
            Intent installer = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installer.setClipData(ClipData.newRawUri("Heimspiel-Update", uri));
            beforeExternal.run();
            activity.startActivityForResult(installer, INSTALL_APK);
            synchronized (lock) {
                setState("installing", "Bestätige das Update im Installationsdialog des Fernsehers.", false, 100);
            }
            emit();
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException unavailable) {
            synchronized (lock) {
                setState("error", "Der Fernseher konnte den APK-Installationsdialog nicht öffnen. Du kannst das Update weiterhin über WLAN installieren.", false, -1);
            }
            emit();
        }
    }

    private void permissionMessage() {
        synchronized (lock) {
            setState("needs_permission", "Erlaube die Installation für Andrés Heimspiel. Kehre anschließend zurück und wähle Installieren.", false, -1);
        }
        emit();
    }

    private boolean installAllowed() {
        try { return activity.getPackageManager().canRequestPackageInstalls(); }
        catch (RuntimeException denied) { return false; }
    }

    @SuppressWarnings("deprecation")
    private PackageInfo currentPackage() throws PackageManager.NameNotFoundException {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return activity.getPackageManager().getPackageInfo(packageName, flags);
    }

    @SuppressWarnings("deprecation")
    private VerifiedApk verifyApk(File file) throws IOException, PackageManager.NameNotFoundException {
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_APK_BYTES) throw new IOException("Keine gültige APK bis 64 MB gefunden.");
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null) throw new IOException("Die Datei ist keine lesbare Android-APK.");
        if (!packageName.equals(archive.packageName)) throw new IOException("Diese APK gehört nicht zu Andrés Heimspiel.");
        PackageInfo current = currentPackage();
        long code = versionCode(archive);
        if (code <= versionCode(current)) throw new IOException("Bitte eine neuere APK wählen. Diese Version ist bereits installiert oder älter.");
        Set<String> installedSigners = signers(current);
        Set<String> archiveSigners = signers(archive);
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || !installedSigners.equals(archiveSigners)) {
            throw new IOException("Die APK hat eine andere Signatur und kann diese Installation nicht aktualisieren.");
        }
        String name = archive.versionName == null ? Long.toString(code) : archive.versionName;
        return new VerifiedApk(code, name);
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) throws IOException {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) result.add(hex(sha256().digest(signature.toByteArray())));
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static Release parseRelease(String text) throws IOException {
        try {
            JSONObject json = new JSONObject(text);
            Object rawCode = json.get("versionCode");
            if (!(rawCode instanceof Integer) && !(rawCode instanceof Long)) throw new JSONException("versionCode");
            long code = ((Number) rawCode).longValue();
            if (code <= 0 || code > Integer.MAX_VALUE) throw new JSONException("versionCode");
            Object rawName = json.get("versionName"), rawUrl = json.get("apkUrl"), rawHash = json.get("sha256");
            if (!(rawName instanceof String) || !(rawUrl instanceof String) || !(rawHash instanceof String)) throw new JSONException("string fields");
            String name = ((String) rawName).trim();
            String url = ((String) rawUrl).trim();
            String hash = ((String) rawHash).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.length() > 80 || name.matches(".*[\\p{Cntrl}].*")) throw new JSONException("versionName");
            if (url.length() > 4096 || !hash.matches("[0-9a-f]{64}")) throw new JSONException("APK URL or SHA256");
            httpsUrl(url);
            String notes = json.opt("notes") instanceof String ? json.getString("notes") : "";
            if (notes.length() > 5000) notes = notes.substring(0, 5000);
            return new Release(code, name, url, hash, notes);
        } catch (JSONException invalid) {
            throw new IOException("Die update.json braucht versionCode, versionName, apkUrl und eine gültige SHA256-Prüfsumme.", invalid);
        }
    }

    private HttpsURLConnection connect(URL initial, String accept, long token) throws IOException {
        URL current = initial;
        for (int redirects = 0; redirects <= 5; redirects++) {
            requireCurrent(token);
            HttpsURLConnection connection = (HttpsURLConnection) current.openConnection();
            activeConnection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Heimspiel-Update/" + installedVersionName);
            try {
                int code = connection.getResponseCode();
                if (code == 200) return connection;
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 5) throw new IOException("Zu viele oder ungültige Weiterleitungen der Updatequelle.");
                    URL target = new URL(current, location);
                    current = httpsUrl(target.toExternalForm());
                    disconnect(connection);
                    continue;
                }
                throw new IOException("Die Updatequelle antwortet mit HTTP " + code + ".");
            } catch (IOException failure) {
                disconnect(connection);
                throw failure;
            }
        }
        throw new IOException("Zu viele Weiterleitungen.");
    }

    private static URL httpsUrl(String value) throws IOException {
        try {
            URL url = new URL(value);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || url.getRef() != null
                    || value.matches(".*[\\p{Cntrl}\\s].*")) {
                throw new MalformedURLException();
            }
            return url;
        } catch (MalformedURLException invalid) {
            throw new IOException("Bitte eine gültige HTTPS-Adresse ohne Zugangsdaten oder Leerzeichen verwenden.", invalid);
        }
    }

    private byte[] readSmall(InputStream in, int limit, long token) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            requireCurrent(token);
            if (out.size() + read > limit) throw new IOException("Die Updatebeschreibung ist größer als 64 KB.");
            out.write(buffer, 0, read);
        }
        if (out.size() == 0) throw new IOException("Die Updatebeschreibung ist leer.");
        return out.toByteArray();
    }

    private void copyBounded(InputStream in, FileOutputStream out, long length, MessageDigest digest, long token) throws IOException {
        byte[] buffer = new byte[32768];
        int read;
        long total = 0;
        int previous = -1;
        while ((read = in.read(buffer)) != -1) {
            requireCurrent(token);
            total += read;
            if (total > MAX_APK_BYTES) throw new IOException("Die APK ist größer als 64 MB.");
            out.write(buffer, 0, read);
            if (digest != null) digest.update(buffer, 0, read);
            int percent = length > 0 ? (int) Math.min(99L, total * 100 / length) : -1;
            if (percent != previous) {
                previous = percent;
                synchronized (lock) { if (isCurrent(token)) progress = percent; }
                emit();
            }
        }
        if (total == 0) throw new IOException("Die APK-Datei ist leer.");
        out.getFD().sync();
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Der Update-Zwischenspeicher konnte nicht angelegt werden.");
    }

    private long begin(String state, String text) {
        long token = generation.incrementAndGet();
        setState(state, text, true, -1);
        return token;
    }

    private void setState(String state, String text, boolean working, int percent) {
        status = state;
        message = text == null ? "Update konnte nicht ausgeführt werden." : text;
        busy = working;
        progress = percent;
    }

    private void submit(long token, Runnable job) {
        try { worker.execute(job); }
        catch (RejectedExecutionException stopped) {
            if (!closed) fail(token, new IOException("Der Update-Dienst ist nicht verfügbar."));
        }
    }

    private boolean isCurrent(long token) { return !closed && generation.get() == token; }

    private void requireCurrent(long token) throws IOException {
        if (!isCurrent(token) || Thread.currentThread().isInterrupted()) throw new IOException("Update abgebrochen.");
    }

    private void fail(long token, Exception failure) {
        synchronized (lock) {
            if (!isCurrent(token)) return;
            String text = failure instanceof IOException ? failure.getMessage()
                    : "Die APK oder Updatequelle konnte nicht geprüft werden.";
            setState("error", text, false, -1);
        }
        emit();
    }

    private void emit() {
        if (closed) return;
        main.post(() -> { if (!closed) callback.accept(getInfoJSON()); });
    }

    private void onMain(Runnable action) {
        if (closed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(() -> { if (!closed) action.run(); });
    }

    private void disconnect(HttpsURLConnection connection) {
        if (connection == null) return;
        connection.disconnect();
        if (activeConnection == connection) activeConnection = null;
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException missing) { throw new IOException("SHA256-Prüfung ist nicht verfügbar.", missing); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static final class Release {
        final long versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String notes;
        Release(long code, String name, String url, String hash, String notes) {
            versionCode = code; versionName = name; apkUrl = url; sha256 = hash; this.notes = notes;
        }
    }

    private static final class VerifiedApk {
        final long versionCode;
        final String versionName;
        VerifiedApk(long code, String name) { versionCode = code; versionName = name; }
    }
}
