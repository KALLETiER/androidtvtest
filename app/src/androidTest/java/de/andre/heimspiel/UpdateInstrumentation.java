package de.andre.heimspiel;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free instrumentation for the actual Android package-verification boundary.
 * Run on an isolated emulator. Fixture arguments must point into target cache/update-qa.
 * No network request or package installation is performed. The optional permission-gate
 * check requires testPermissionGate=true and refuses to proceed if installation is allowed.
 * Alternatively, testInstallerDialog=true explicitly opens the emulator's normal installer
 * without confirming installation; these two options are mutually exclusive. The runner
 * keeps its Activity alive until the visible install/update confirmation is ready, so
 * cleanup cannot revoke the FileProvider grant while Android is still staging the APK.
 */
public final class UpdateInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final JSONArray checks = new JSONArray();
    private final JSONArray errors = new JSONArray();
    private int skipped;
    private Activity activity;
    private UpdateManager manager;
    private File stagedCopy;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        try {
            boolean testPermissionGate = Boolean.parseBoolean(arguments.getString("testPermissionGate", "false"));
            boolean testInstallerDialog = Boolean.parseBoolean(arguments.getString("testInstallerDialog", "false"));
            require(!(testPermissionGate && testInstallerDialog), "Permission-gate and installer-dialog modes are mutually exclusive");
            Context target = getTargetContext();
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .setClassName(target.getPackageName(), "de.andre.heimspiel.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            waitForIdleSync();
            Field updateField = activity.getClass().getDeclaredField("updates");
            updateField.setAccessible(true);
            manager = (UpdateManager) updateField.get(activity);
            require(manager != null, "MainActivity did not create UpdateManager");

            JSONObject installed = manager.getInfoJSON();
            long installedCode = installed.getLong("installedVersionCode");
            long expectedNewCode = Long.parseLong(arguments.getString("expectedNewVersionCode",
                    Long.toString(installedCode + 1)));
            require(installedCode > 0, "Installed package version could not be read");

            File newer = fixture(target, "newerApk");
            File older = fixture(target, "oldApk");
            File wrongSigner = fixture(target, "wrongSignerApk");
            File wrongPackage = fixture(target, "wrongPackageApk");
            Method parse = method(UpdateManager.class, "parseRelease", String.class);
            Method verify = method(UpdateManager.class, "verifyApk", File.class);

            check("installed-version", () -> {
                require(!installed.getString("installedVersionName").isEmpty(), "Version name missing");
                require(!installed.getBoolean("ready"), "A fresh manager unexpectedly has a staged update");
            });
            check("manifest-valid-https", () -> {
                Object release = invoke(parse, null, validManifest(expectedNewCode).toString());
                require(longField(release, "versionCode") == expectedNewCode, "Manifest version changed");
            });
            check("manifest-http-rejected", () -> expectIOException(parse, null,
                    validManifest(expectedNewCode).put("apkUrl", "http://example.org/update.apk").toString(), "HTTPS"));
            check("manifest-missing-hash-rejected", () -> {
                JSONObject manifest = validManifest(expectedNewCode); manifest.remove("sha256");
                expectIOException(parse, null, manifest.toString(), "SHA256");
            });
            check("manifest-invalid-version-rejected", () -> expectIOException(parse, null,
                    validManifest(expectedNewCode).put("versionCode", 6.5).toString(), "versionCode"));
            check("http-source-does-not-replace-source", () -> {
                String before = manager.getInfoJSON().getString("source");
                final boolean[] accepted = {true};
                runOnMainSync(() -> accepted[0] = manager.setSource("http://example.org/update.json"));
                require(!accepted[0], "HTTP source was accepted");
                require(before.equals(manager.getInfoJSON().getString("source")), "Rejected source replaced saved source");
            });
            check("install-without-verified-apk-rejected", () -> {
                runOnMainSync(manager::install);
                JSONObject info = manager.getInfoJSON();
                require("error".equals(info.getString("status")), "Install without APK did not fail");
                require(!info.getBoolean("busy") && !info.getBoolean("ready"), "Empty install entered a working/ready state");
            });
            check("older-apk-rejected", () -> expectIOException(verify, manager, older, "neuere"));
            check("same-version-apk-rejected", () -> expectIOException(verify, manager,
                    new File(target.getApplicationInfo().sourceDir), "neuere"));
            check("wrong-package-rejected", () -> expectIOException(verify, manager, wrongPackage, "gehört nicht"));
            check("wrong-signer-rejected", () -> expectIOException(verify, manager, wrongSigner, "Signatur"));
            check("newer-same-signer-accepted", () -> {
                Object verified = invoke(verify, manager, newer);
                require(longField(verified, "versionCode") == expectedNewCode, "Wrong verified APK version");
            });
            check("verified-apk-staged-not-installed", () -> {
                Object verified = invoke(verify, manager, newer);
                invoke(method(UpdateManager.class, "ensureDirectory"), manager);
                File qa = new File(target.getCacheDir(), "update-qa");
                stagedCopy = File.createTempFile("stage-copy-", ".apk", qa);
                Files.copy(newer.toPath(), stagedCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Field generation = UpdateManager.class.getDeclaredField("generation"); generation.setAccessible(true);
                long token = ((AtomicLong) generation.get(manager)).get();
                invoke(method(UpdateManager.class, "stage", File.class, verified.getClass(), long.class),
                        manager, stagedCopy, verified, token);
                stagedCopy = null; // stage moved this copy; the original fixture is unchanged.
                JSONObject info = manager.getInfoJSON();
                require(info.getBoolean("ready"), "Staged APK not ready");
                require("ready".equals(info.getString("status")), "Staging did not stop before installation");
                require(info.getLong("readyVersionCode") == expectedNewCode, "Staged version does not match verified APK");
                require(!info.getBoolean("busy"), "Staged APK is unexpectedly busy");
            });

            if (testInstallerDialog) {
                check("system-installer-dialog-opens-without-confirming", () -> {
                    require(manager.getInfoJSON().getBoolean("ready"), "Installer smoke test requires a staged APK");
                    require(manager.getInfoJSON().getBoolean("installAllowed"),
                            "Installer smoke test requires installation permission on the isolated emulator");
                    runOnMainSync(manager::install);
                    long deadline = SystemClock.uptimeMillis() + 45000;
                    JSONObject info;
                    do {
                        SystemClock.sleep(25);
                        info = manager.getInfoJSON();
                    } while (info.getBoolean("busy") && SystemClock.uptimeMillis() < deadline);
                    require("installing".equals(info.getString("status")) && !info.getBoolean("busy"),
                            "System installer did not open: " + info);
                    require(info.getBoolean("ready"), "Opening installer lost the verified APK");
                    awaitInstallerConfirmation();
                });
            } else if (testPermissionGate) {
                check("permission-denied-prevents-installer", () -> {
                    require(manager.getInfoJSON().getBoolean("ready"), "Permission test requires a staged APK");
                    require(!manager.getInfoJSON().getBoolean("installAllowed"),
                            "Refusing permission test: package installation is allowed on this emulator");
                    runOnMainSync(manager::install);
                    long deadline = SystemClock.uptimeMillis() + 15000;
                    JSONObject info;
                    do {
                        SystemClock.sleep(25);
                        info = manager.getInfoJSON();
                    } while (info.getBoolean("busy") && SystemClock.uptimeMillis() < deadline);
                    require("needs_permission".equals(info.getString("status")),
                            "Missing permission did not prevent installer: " + info);
                    require(info.getBoolean("ready"), "Permission denial lost the verified APK");
                });
            } else {
                skip("permission-denied-prevents-installer", "Pass testPermissionGate=true after denying REQUEST_INSTALL_PACKAGES on the isolated emulator.");
            }
        } catch (Throwable fatal) {
            record("runner-setup", false, concise(fatal));
        } finally {
            if (stagedCopy != null) stagedCopy.delete();
            if (activity != null) runOnMainSync(activity::finish);
            waitForIdleSync();
            Bundle results = new Bundle();
            results.putBoolean("passed", errors.length() == 0);
            results.putInt("checkCount", checks.length());
            results.putInt("skippedCount", skipped);
            results.putString("checks", checks.toString());
            results.putString("errors", errors.toString());
            finish(errors.length() == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, results);
        }
    }

    private static JSONObject validManifest(long code) throws Exception {
        return new JSONObject().put("versionCode", code).put("versionName", "0.6.0")
                .put("apkUrl", "https://example.org/heimspiel.apk")
                .put("sha256", "0000000000000000000000000000000000000000000000000000000000000000")
                .put("notes", "Instrumentation fixture; no download is performed.");
    }

    /** Observe only: never click the Android install/update confirmation. */
    private void awaitInstallerConfirmation() {
        AccessibilityServiceInfo service = getUiAutomation().getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        getUiAutomation().setServiceInfo(service);
        long deadline = SystemClock.uptimeMillis() + 15000;
        String lastObserved = "No accessible active window";
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                InstallerUi state = new InstallerUi();
                try { inspectInstallerNode(root, state); }
                finally { root.recycle(); }
                lastObserved = state.text.toString();
                String text = lastObserved.toLowerCase(Locale.ROOT);
                if (state.installer && (text.contains("problem parsing") || text.contains("parse error")))
                    throw new AssertionError("Android installer rejected the APK: " + lastObserved);
                if (state.installer && state.confirmationReady) return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No enabled INSTALL/UPDATE confirmation in Android package installer: " + lastObserved);
    }

    private static void inspectInstallerNode(AccessibilityNodeInfo node, InstallerUi state) {
        CharSequence packageName = node.getPackageName();
        if (packageName != null && packageName.toString().contains("packageinstaller")) state.installer = true;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) state.text.append(text).append(' ');
        String label = text == null ? "" : text.toString().trim().toUpperCase(Locale.ROOT);
        if ("android:id/button1".equals(node.getViewIdResourceName())
                && ("INSTALL".equals(label) || "UPDATE".equals(label))
                && node.isEnabled() && node.isVisibleToUser()) state.confirmationReady = true;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                try { inspectInstallerNode(child, state); }
                finally { child.recycle(); }
            }
        }
    }

    private static final class InstallerUi {
        boolean installer;
        boolean confirmationReady;
        final StringBuilder text = new StringBuilder();
    }

    private File fixture(Context target, String key) throws IOException {
        String path = arguments.getString(key);
        if (path == null || path.isEmpty()) throw new IOException("Missing fixture argument: " + key);
        File root = new File(target.getCacheDir(), "update-qa").getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile() || !file.canRead())
            throw new IOException("Fixture must be a readable file under target cache/update-qa: " + key);
        return file;
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments); method.setAccessible(true); return method;
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private static long longField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.getLong(target);
    }

    private static void expectIOException(Method method, Object target, Object argument, String messagePart) throws Exception {
        try { invoke(method, target, argument); }
        catch (IOException expected) {
            require(expected.getMessage() != null && expected.getMessage().contains(messagePart),
                    "Rejected for the wrong reason: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Invalid input was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void check(String name, CheckedAction action) {
        try { action.run(); record(name, true, ""); }
        catch (Throwable failure) { record(name, false, concise(failure)); }
    }

    private void skip(String name, String reason) {
        skipped++;
        try { checks.put(new JSONObject().put("name", name).put("status", "skipped").put("detail", reason)); }
        catch (Exception ignored) { }
    }

    private void record(String name, boolean passed, String detail) {
        try {
            JSONObject result = new JSONObject().put("name", name).put("status", passed ? "passed" : "failed");
            if (!detail.isEmpty()) result.put("detail", detail);
            checks.put(result);
            if (!passed) errors.put(result);
            Bundle progress = new Bundle(); progress.putString("check", result.toString()); sendStatus(0, progress);
        } catch (Exception ignored) { }
    }

    private static String concise(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }

    private interface CheckedAction { void run() throws Exception; }
}
