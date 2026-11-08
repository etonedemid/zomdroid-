/*
 * Zomdroid Direct Steam Game & Workshop Mod Downloader Module
 *
 * Полный, готовый к компиляции модуль прямой загрузки файлов Project Zomboid (Linux Depot)
 * и модификаций Steam Workshop на Android без участия ПК для интеграции в лаунчер Zomdroid:
 * - Оригинальный репозиторий: [liamelui/zomdroid](https://github.com/liamelui/zomdroid)
 * - Активный форк: [etonedemid/zomdroid-](https://github.com/etonedemid/zomdroid-)
 *
 * Основано на актуальном API библиотеки JavaSteam 1.8.0:
 * - Репозиторий JavaSteam: [Longi94/JavaSteam](https://github.com/Longi94/JavaSteam)
 * - Релизы JavaSteam (v1.8.0): [Longi94/JavaSteam Releases](https://github.com/Longi94/JavaSteam/releases)
 * - Maven-артефакты: [in.dragonbra:javasteam](https://mvnrepository.com/artifact/in.dragonbra/javasteam)
 *   и [in.dragonbra:javasteam-depotdownloader](https://mvnrepository.com/artifact/in.dragonbra)
 * - Документация Android-интеграции JavaSteam: [STEAM_IMPLEMENTATION.md](https://github.com/The412Banner/BannerHub/blob/main/game-store-reports/STEAM_IMPLEMENTATION.md)
 *
 * Спецификация депо Project Zomboid и модов Steam Workshop:
 * - Руководство по прямой загрузке на Android: [Downloading the PZ files without a PC](https://www.reddit.com/r/zomdroid/comments/1rkg7t3/downloading_the_pz_files_without_a_pc/)
 * - Загрузка игры и модов по манифестам и pubfile: [How to download PZ game files and Workshop mods](https://www.reddit.com/r/zomdroid/comments/1rtjsj3/how_to_download_pz_game_files_and_workshop_mods/)
 * - Справочник манифестов Build 42.7: [Downloading PZ from Steam: different manifests](https://www.reddit.com/r/zomdroid/comments/1q95qqo/donwloading_pz_from_steam_different_manifests/)
 * - Подготовка структуры файлов Zomdroid: [Guide: Creating a ZIP for the Zomdroid launcher](https://www.reddit.com/r/zomdroid/comments/1o5qo06/guide_creating_a_zip_for_the_zomdroid_launcher/)
 * - База данных SteamDB Linux-депо 108603: [SteamDB Depot 108603](https://steamdb.info/depot/108603/)
 *   и [SteamDB Depot 108603 Manifests](https://steamdb.info/depot/108603/manifests/)
 *
 * =========================================================================================
 * ИНСТРУКЦИЯ ПО ПОДКЛЮЧЕНИЮ В GRADLE (app/build.gradle.kts в Zomdroid):
 * =========================================================================================
 * dependencies {
 *     // Ядро JavaSteam 1.8.0 и модуль DepotDownloader 1.8.0
 *     implementation("in.dragonbra:javasteam:1.8.0")
 *     implementation("in.dragonbra:javasteam-depotdownloader:1.8.0")
 *
 *     // Криптография и сжатие чанков SteamPipe (XZ / LZMA2 и ZSTD JNI AAR для Android)
 *     implementation("org.bouncycastle:bcprov-jdk18on:1.79")
 *     implementation("com.google.protobuf:protobuf-java:4.29.2")
 *     implementation("org.tukaani:xz:1.10")
 *     implementation("com.github.luben:zstd-jni:1.5.6-8@aar")
 *     implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 *
 *     // Аппаратное шифрование токенов (Android Keystore AES256-GCM) и генерация QR-кода
 *     implementation("androidx.security:security-crypto:1.1.0-alpha06")
 *     implementation("com.google.zxing:core:3.5.3")
 *     implementation("androidx.core:core:1.13.1")
 * }
 *
 * =========================================================================================
 * РАЗРЕШЕНИЯ И СЕРВИС В ANDROIDMANIFEST.XML:
 * =========================================================================================
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 * <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
 * <uses-permission android:name="android.permission.WAKE_LOCK" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <application ...>
 *     <service
 *         android:name="com.zomdroid.steam.SteamDownloadModule$SteamDownloadService"
 *         android:enabled="true"
 *         android:exported="false"
 *         android:foregroundServiceType="dataSync" />
 * </application>
 */

package com.zomdroid.steam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.protobuf.ByteString;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.depotdownloader.data.PubFileItem;
import in.dragonbra.javasteam.enums.EOSType;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Response;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.PublishedFileDetails;
import in.dragonbra.javasteam.rpc.service.PublishedFile;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.QrAuthSession;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.DepotChunk;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamcloud.UGCDetailsCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.LoggedOffCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.LoggedOnCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.types.SteamID;
import in.dragonbra.javasteam.types.UGCHandle;

/**
 * Единый фасад и контейнер всех классов модуля прямой загрузки Project Zomboid и модов Steam Workshop
 * для интеграции в лаунчер Zomdroid ([liamelui/zomdroid](https://github.com/liamelui/zomdroid),
 * [etonedemid/zomdroid-](https://github.com/etonedemid/zomdroid-)).
 */
public final class SteamDownloadModule {

    private static final String TAG = "ZomdroidSteamModule";

    private SteamDownloadModule() {
        // Utility container class
    }

    // =============================================================================================
    // 1. КОНСТАНТЫ И СПЕЦИФИКАЦИЯ ДЕПО PROJECT ZOMBOID
    // =============================================================================================

    /**
     * Константы депо Project Zomboid и параметров раскладки файлов для лаунчера Zomdroid.
     * См. [SteamDB Depot 108603](https://steamdb.info/depot/108603/) и
     * [How to download PZ game files and Workshop mods](https://www.reddit.com/r/zomdroid/comments/1rtjsj3/how_to_download_pz_game_files_and_workshop_mods/).
     */
    public static final class ZomboidSteamConfig {
        /** Steam AppID игры Project Zomboid. */
        public static final int PZ_APP_ID = 108600;

        /** Linux x86_64 DepotID игры Project Zomboid, необходимый для Box64 в Zomdroid. */
        public static final int PZ_LINUX_DEPOT_ID = 108603;

        /** Стабильная ветка Build 41 (например, 41.78.16). */
        public static final String BRANCH_PUBLIC = "public";

        /** Экспериментальная бета-ветка Build 42 (Unstable). */
        public static final String BRANCH_UNSTABLE = "unstable";

        /**
         * Манифест Build 42.7 (7 апреля 2025 г.) для депо 108603: последняя версия Build 42,
         * официально совместимая с базовым рантаймом [liamelui/zomdroid](https://github.com/liamelui/zomdroid)
         * и [etonedemid/zomdroid-](https://github.com/etonedemid/zomdroid-).
         * См. [Downloading PZ from Steam: different manifests](https://www.reddit.com/r/zomdroid/comments/1q95qqo/donwloading_pz_from_steam_different_manifests/).
         */
        public static final long MANIFEST_BUILD_42_7 = 7735218517840308728L;

        /** Константа для запроса самого свежего манифеста выбранной ветки из PICS. */
        public static final long MANIFEST_LATEST = DepotDownloader.INVALID_MANIFEST_ID;

        /** Главный 64-битный исполняемый файл Linux-версии Project Zomboid. */
        public static final String MAIN_EXECUTABLE = "ProjectZomboid64";

        /** Скрипт запуска Linux-версии Project Zomboid. */
        public static final String LAUNCH_SCRIPT = "projectzomboid.sh";

        /** Подпапка внутри депо 108603, в которой располагаются файлы игры. */
        public static final String DEPOT_ROOT_SUBFOLDER = "projectzomboid";

        /** Относительный путь к папке модов внутри домашней директории инстанса Zomdroid. */
        public static final String ZOMBOID_MODS_REL_PATH = "Zomboid/mods";

        private ZomboidSteamConfig() {
        }
    }

    /**
     * Предустановленные профили веток Project Zomboid для UI выбора версии в Zomdroid.
     */
    public enum ZomboidBranchPreset {
        /** Стабильная ветка Build 41 (`public`, последний манифест). */
        BUILD_41_STABLE(ZomboidSteamConfig.BRANCH_PUBLIC, ZomboidSteamConfig.MANIFEST_LATEST),

        /** Бета-ветка Build 42.7 (`unstable`, фиксированный манифест `7735218517840308728`). */
        BUILD_42_7_COMPATIBLE(ZomboidSteamConfig.BRANCH_UNSTABLE, ZomboidSteamConfig.MANIFEST_BUILD_42_7),

        /** Актуальная бета-ветка Build 42 (`unstable`, последний манифест). */
        BUILD_42_UNSTABLE_LATEST(ZomboidSteamConfig.BRANCH_UNSTABLE, ZomboidSteamConfig.MANIFEST_LATEST);

        private final String branchName;
        private final long defaultManifestId;

        ZomboidBranchPreset(String branchName, long defaultManifestId) {
            this.branchName = branchName;
            this.defaultManifestId = defaultManifestId;
        }

        public String getBranchName() {
            return branchName;
        }

        public long getDefaultManifestId() {
            return defaultManifestId;
        }
    }

    /**
     * Конфигурация задачи загрузки игры или модов Workshop.
     */
    public static final class DownloadRequest {
        private final int appId;
        private final int depotId;
        private final String branch;
        private final String branchPassword;
        private final long manifestId;
        private final File targetInstanceDir;
        private final File zomboidUserDir;
        private final List<Long> workshopPublishedFileIds;
        private final boolean useHighLevelDepotDownloader;
        private final boolean exportInstanceZip;
        private final File optionalOutputZipFile;
        private final int maxParallelChunks;

        private DownloadRequest(Builder builder) {
            this.appId = builder.appId;
            this.depotId = builder.depotId;
            this.branch = builder.branch;
            this.branchPassword = builder.branchPassword;
            this.manifestId = builder.manifestId;
            this.targetInstanceDir = Objects.requireNonNull(builder.targetInstanceDir, "targetInstanceDir");
            this.zomboidUserDir = builder.zomboidUserDir != null
                    ? builder.zomboidUserDir
                    : new File(builder.targetInstanceDir, "Zomboid");
            this.workshopPublishedFileIds = Collections.unmodifiableList(new ArrayList<>(builder.workshopPublishedFileIds));
            this.useHighLevelDepotDownloader = builder.useHighLevelDepotDownloader;
            this.exportInstanceZip = builder.exportInstanceZip;
            this.optionalOutputZipFile = builder.optionalOutputZipFile;
            this.maxParallelChunks = builder.maxParallelChunks;
        }

        public int getAppId() {
            return appId;
        }

        public int getDepotId() {
            return depotId;
        }

        public String getBranch() {
            return branch;
        }

        public String getBranchPassword() {
            return branchPassword;
        }

        public long getManifestId() {
            return manifestId;
        }

        public File getTargetInstanceDir() {
            return targetInstanceDir;
        }

        public File getZomboidUserDir() {
            return zomboidUserDir;
        }

        public File getModsDir() {
            if (zomboidUserDir.getName().equalsIgnoreCase("Zomboid")) {
                return new File(zomboidUserDir, "mods");
            }
            return new File(zomboidUserDir, ZomboidSteamConfig.ZOMBOID_MODS_REL_PATH);
        }

        public List<Long> getWorkshopPublishedFileIds() {
            return workshopPublishedFileIds;
        }

        public boolean isUseHighLevelDepotDownloader() {
            return useHighLevelDepotDownloader;
        }

        public boolean isExportInstanceZip() {
            return exportInstanceZip;
        }

        public File getOptionalOutputZipFile() {
            return optionalOutputZipFile;
        }

        public int getMaxParallelChunks() {
            return maxParallelChunks;
        }

        public static final class Builder {
            private int appId = ZomboidSteamConfig.PZ_APP_ID;
            private int depotId = ZomboidSteamConfig.PZ_LINUX_DEPOT_ID;
            private String branch = ZomboidSteamConfig.BRANCH_PUBLIC;
            private String branchPassword = null;
            private long manifestId = ZomboidSteamConfig.MANIFEST_LATEST;
            private File targetInstanceDir;
            private File zomboidUserDir;
            private final List<Long> workshopPublishedFileIds = new ArrayList<>();
            private boolean useHighLevelDepotDownloader = false;
            private boolean exportInstanceZip = false;
            private File optionalOutputZipFile = null;
            private int maxParallelChunks = 8;

            public Builder(File targetInstanceDir) {
                this.targetInstanceDir = Objects.requireNonNull(targetInstanceDir, "targetInstanceDir");
            }

            public Builder withPreset(ZomboidBranchPreset preset) {
                this.branch = preset.getBranchName();
                this.manifestId = preset.getDefaultManifestId();
                return this;
            }

            public Builder withBranch(String branch) {
                this.branch = (branch == null || branch.trim().isEmpty())
                        ? ZomboidSteamConfig.BRANCH_PUBLIC
                        : branch.trim();
                return this;
            }

            public Builder withBranchPassword(String branchPassword) {
                this.branchPassword = branchPassword;
                return this;
            }

            public Builder withManifestId(long manifestId) {
                this.manifestId = manifestId > 0 ? manifestId : ZomboidSteamConfig.MANIFEST_LATEST;
                return this;
            }

            public Builder withZomboidUserDir(File zomboidUserDir) {
                this.zomboidUserDir = zomboidUserDir;
                return this;
            }

            public Builder addWorkshopMod(long publishedFileId) {
                if (publishedFileId > 0) {
                    this.workshopPublishedFileIds.add(publishedFileId);
                }
                return this;
            }

            public Builder withWorkshopMods(Collection<Long> publishedFileIds) {
                if (publishedFileIds != null) {
                    for (Long id : publishedFileIds) {
                        if (id != null && id > 0) {
                            this.workshopPublishedFileIds.add(id);
                        }
                    }
                }
                return this;
            }

            public Builder withHighLevelDepotDownloader(boolean enabled) {
                this.useHighLevelDepotDownloader = enabled;
                return this;
            }

            public Builder withExportInstanceZip(boolean exportZip, File zipFile) {
                this.exportInstanceZip = exportZip;
                this.optionalOutputZipFile = zipFile;
                return this;
            }

            public Builder withMaxParallelChunks(int threads) {
                this.maxParallelChunks = Math.max(1, Math.min(16, threads));
                return this;
            }

            public DownloadRequest build() {
                return new DownloadRequest(this);
            }
        }
    }

    // =============================================================================================
    // 2. БЕЗОПАСНОЕ ХРАНИЛИЩЕ СЕССИИ STEAM (БЕЗ ХРАНЕНИЯ ПАРОЛЯ В ОТКРЫТОМ ВИДЕ)
    // =============================================================================================

    /**
     * Защищённое хранилище JWT-токенов сессии Steam (`refreshToken` и `guardData`)
     * на базе Android Keystore (`EncryptedSharedPreferences` с `AES256_GCM`).
     * Пароль пользователя никогда не сохраняется на диск и затирается в ОЗУ сразу после аутентификации.
     */
    public static final class SecureSteamTokenStore {
        private static final String PREFS_FILE_NAME = "zomdroid_steam_secure_session";
        private static final String KEY_ACCOUNT_NAME = "account_name";
        private static final String KEY_REFRESH_TOKEN = "refresh_token";
        private static final String KEY_GUARD_DATA = "guard_data";
        private static final String KEY_STEAM_ID_64 = "steam_id_64";
        private static final String KEY_CELL_ID = "cell_id";

        private final SharedPreferences encryptedPrefs;

        public SecureSteamTokenStore(Context context) {
            Context appContext = context.getApplicationContext();
            SharedPreferences prefs;
            try {
                MasterKey masterKey = new MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                prefs = EncryptedSharedPreferences.create(
                        appContext,
                        PREFS_FILE_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception e) {
                Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to private mode", e);
                prefs = appContext.getSharedPreferences(PREFS_FILE_NAME + "_fallback", Context.MODE_PRIVATE);
            }
            this.encryptedPrefs = prefs;
        }

        public synchronized void saveSession(
                String accountName,
                String refreshToken,
                String newGuardData,
                long steamId64,
                int cellId
        ) {
            SharedPreferences.Editor editor = encryptedPrefs.edit()
                    .putString(KEY_ACCOUNT_NAME, accountName != null ? accountName : "")
                    .putString(KEY_REFRESH_TOKEN, refreshToken != null ? refreshToken : "")
                    .putLong(KEY_STEAM_ID_64, steamId64)
                    .putInt(KEY_CELL_ID, cellId);
            if (newGuardData != null && !newGuardData.isEmpty()) {
                editor.putString(KEY_GUARD_DATA, newGuardData);
            }
            editor.apply();
        }

        public synchronized void updateCellId(int cellId) {
            encryptedPrefs.edit().putInt(KEY_CELL_ID, cellId).apply();
        }

        public synchronized String getAccountName() {
            return encryptedPrefs.getString(KEY_ACCOUNT_NAME, "");
        }

        /**
         * В JavaSteam 1.8.0 `refreshToken` передаётся в `LogOnDetails.setAccessToken(...)`
         * при повторном входе без пароля (см. [STEAM_IMPLEMENTATION.md](https://github.com/The412Banner/BannerHub/blob/main/game-store-reports/STEAM_IMPLEMENTATION.md)).
         */
        public synchronized String getRefreshToken() {
            return encryptedPrefs.getString(KEY_REFRESH_TOKEN, "");
        }

        public synchronized String getGuardData() {
            return encryptedPrefs.getString(KEY_GUARD_DATA, null);
        }

        public synchronized long getSteamId64() {
            return encryptedPrefs.getLong(KEY_STEAM_ID_64, 0L);
        }

        public synchronized int getCellId() {
            return encryptedPrefs.getInt(KEY_CELL_ID, 0);
        }

        public synchronized boolean hasSavedSession() {
            String user = getAccountName();
            String token = getRefreshToken();
            return user != null && !user.isEmpty() && token != null && !token.isEmpty();
        }

        public synchronized void clearSession() {
            encryptedPrefs.edit().clear().apply();
        }
    }

    // =============================================================================================
    // 3. МОБИЛЬНОЕ ПОДТВЕРЖДЕНИЕ STEAM GUARD, 2FA И QR-АВТОРИЗАЦИЯ
    // =============================================================================================

    /**
     * Слушатель событий авторизации Steam для отображения QR-кода или диалога ввода кода в UI Zomdroid.
     */
    public interface SteamAuthCallback {
        /** Вызывается при генерации или обновлении ссылки QR-кода (каждые ~30 секунд). */
        void onQrChallengeUrlUpdated(String challengeUrl, Bitmap qrBitmap);

        /** Вызывается, когда пользователю отправлено push-уведомление в мобильное приложение Steam. */
        void onAwaitingMobileConfirmation();

        /** Вызывается, если требуется ручной ввод 2FA-кода Steam Guard или кода из письма. */
        void onSteamGuardCodeRequired(boolean isEmailCode, String emailDomainOrHint, boolean previousIncorrect);

        /** Вызывается после успешного входа в Steam и сохранения `refreshToken`. */
        void onAuthenticated(String accountName, long steamId64);

        /** Вызывается при ошибке авторизации. */
        void onAuthError(String errorMessage, Throwable cause);
    }

    /**
     * Генератор QR-кода (`Bitmap`) из `challengeUrl`, возвращаемого `QrAuthSession.getChallengeUrl()`.
     */
    public static final class SteamQrBitmapGenerator {
        private SteamQrBitmapGenerator() {
        }

        public static Bitmap createQrBitmap(String content, int sizePx) throws WriterException {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new MultiFormatWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    hints
            );
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        }
    }

    /**
     * Реализация `IAuthenticator` из [Longi94/JavaSteam](https://github.com/Longi94/JavaSteam),
     * поддерживающая:
     * 1) Мобильное push-подтверждение в приложении Steam Mobile (`acceptDeviceConfirmation()`).
     * 2) Асинхронный ввод 2FA TOTP или Email-кода через `CompletableFuture<String>`.
     */
    public static final class ZomdroidAuthenticator implements IAuthenticator {
        private final SteamAuthCallback authCallback;
        private final boolean preferMobilePushConfirmation;
        private final AtomicReference<CompletableFuture<String>> pendingCodeFuture = new AtomicReference<>();

        public ZomdroidAuthenticator(SteamAuthCallback authCallback, boolean preferMobilePushConfirmation) {
            this.authCallback = Objects.requireNonNull(authCallback, "authCallback");
            this.preferMobilePushConfirmation = preferMobilePushConfirmation;
        }

        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            if (preferMobilePushConfirmation) {
                authCallback.onAwaitingMobileConfirmation();
                return CompletableFuture.completedFuture(true);
            }
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingCodeFuture.set(future);
            authCallback.onSteamGuardCodeRequired(false, "Steam Mobile Authenticator", previousCodeWasIncorrect);
            return future;
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingCodeFuture.set(future);
            authCallback.onSteamGuardCodeRequired(true, email != null ? email : "", previousCodeWasIncorrect);
            return future;
        }

        /**
         * Вызывается из UI Zomdroid, когда пользователь ввёл код Steam Guard вручную.
         */
        public void submitSteamGuardCode(String code) {
            CompletableFuture<String> future = pendingCodeFuture.getAndSet(null);
            if (future != null && !future.isDone()) {
                future.complete(code != null ? code.trim() : "");
            }
        }

        public void cancelPendingCode() {
            CompletableFuture<String> future = pendingCodeFuture.getAndSet(null);
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    // =============================================================================================
    // 4. МЕНЕДЖЕР СЕССИИ STEAMCLIENT, PICS И АВТОРИЗАЦИИ
    // =============================================================================================

    /**
     * Потокобезопасный менеджер подключения к серверам Steam CM, авторизации (QR / Steam Guard / RefreshToken / Anonymous)
     * и управления обработчиками `SteamApps`, `SteamContent`, `SteamUnifiedMessages` и `SteamCloud`.
     */
    public static final class SteamSessionManager implements Closeable {
        private static final long CONNECT_TIMEOUT_SEC = 20L;
        private static final long LOGIN_TIMEOUT_SEC = 30L;
        private static final int QR_POLL_INTERVAL_MS = 3000;
        private static final int QR_BITMAP_SIZE_PX = 512;

        static {
            // Регистрация BouncyCastle для криптографии SteamKit2 / JavaSteam на Android
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(new BouncyCastleProvider(), 1);
            } else {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
                Security.insertProviderAt(new BouncyCastleProvider(), 1);
            }
        }

        private final Context appContext;
        private final SecureSteamTokenStore tokenStore;
        private final AtomicBoolean pumpRunning = new AtomicBoolean(false);
        private final AtomicBoolean isLoggedOn = new AtomicBoolean(false);
        private final List<License> ownedLicenses = new CopyOnWriteArrayList<>();

        private SteamClient steamClient;
        private CallbackManager callbackManager;
        private SteamUser steamUser;
        private SteamApps steamApps;
        private SteamContent steamContent;
        private SteamUnifiedMessages unifiedMessages;
        private SteamCloud steamCloud;
        private PublishedFile publishedFileService;
        private Thread callbackPumpThread;

        private volatile CompletableFuture<Void> connectFuture;
        private volatile CompletableFuture<LoggedOnCallback> logOnFuture;
        private volatile CompletableFuture<List<License>> licenseListFuture;
        private volatile ZomdroidAuthenticator activeAuthenticator;

        public SteamSessionManager(Context context) {
            this.appContext = context.getApplicationContext();
            this.tokenStore = new SecureSteamTokenStore(this.appContext);
        }

        public SecureSteamTokenStore getTokenStore() {
            return tokenStore;
        }

        public SteamClient getSteamClient() {
            return steamClient;
        }

        public SteamApps getSteamApps() {
            return steamApps;
        }

        public SteamContent getSteamContent() {
            return steamContent;
        }

        public SteamCloud getSteamCloud() {
            return steamCloud;
        }

        public PublishedFile getPublishedFileService() {
            return publishedFileService;
        }

        public List<License> getOwnedLicenses() {
            return new ArrayList<>(ownedLicenses);
        }

        public boolean isLoggedOn() {
            return isLoggedOn.get() && steamClient != null && steamClient.isConnected();
        }

        /**
         * Передача кода Steam Guard из UI в активную сессию авторизации.
         */
        public void submitGuardCode(String code) {
            ZomdroidAuthenticator auth = this.activeAuthenticator;
            if (auth != null) {
                auth.submitSteamGuardCode(code);
            }
        }

        /**
         * Инициализирует `SteamClient` с протоколом TCP и `withDirectoryFetch(true)`
         * (обязательно для Android согласно [STEAM_IMPLEMENTATION.md](https://github.com/The412Banner/BannerHub/blob/main/game-store-reports/STEAM_IMPLEMENTATION.md))
         * и устанавливает соединение с сервером Steam CM.
         */
        public synchronized void ensureConnected() throws IOException, InterruptedException, ExecutionException, TimeoutException {
            if (steamClient != null && steamClient.isConnected()) {
                return;
            }
            close();

            int savedCellId = tokenStore.getCellId();
            SteamConfiguration config = SteamConfiguration.create(builder -> {
                builder.withProtocolTypes(EnumSet.of(ProtocolTypes.TCP));
                builder.withDirectoryFetch(true);
                builder.withCellID(savedCellId);
            });

            steamClient = new SteamClient(config);
            callbackManager = new CallbackManager(steamClient);

            steamUser = steamClient.getHandler(SteamUser.class);
            steamApps = steamClient.getHandler(SteamApps.class);
            steamContent = steamClient.getHandler(SteamContent.class);
            unifiedMessages = steamClient.getHandler(SteamUnifiedMessages.class);
            steamCloud = steamClient.getHandler(SteamCloud.class);

            if (unifiedMessages != null) {
                publishedFileService = unifiedMessages.createService(PublishedFile.class);
            }

            registerCallbacks();
            startCallbackPump();

            connectFuture = new CompletableFuture<>();
            steamClient.connect();
            connectFuture.get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS);
        }

        private void registerCallbacks() {
            callbackManager.subscribe(ConnectedCallback.class, callback -> {
                Log.i(TAG, "Connected to Steam CM server");
                CompletableFuture<Void> cf = connectFuture;
                if (cf != null && !cf.isDone()) {
                    cf.complete(null);
                }
            });

            callbackManager.subscribe(DisconnectedCallback.class, callback -> {
                Log.w(TAG, "Disconnected from Steam CM (userInitiated=" + callback.isUserInitiated() + ")");
                isLoggedOn.set(false);
                CompletableFuture<Void> cf = connectFuture;
                if (cf != null && !cf.isDone()) {
                    cf.completeExceptionally(new IOException("Disconnected from Steam CM before handshake completed"));
                }
                CompletableFuture<LoggedOnCallback> lf = logOnFuture;
                if (lf != null && !lf.isDone()) {
                    lf.completeExceptionally(new IOException("Disconnected from Steam CM during logon"));
                }
            });

            callbackManager.subscribe(LoggedOnCallback.class, callback -> {
                if (callback.getResult() == EResult.OK) {
                    isLoggedOn.set(true);
                    tokenStore.updateCellId(callback.getCellID());
                    Log.i(TAG, "Logged into Steam successfully, cellId=" + callback.getCellID());
                } else {
                    isLoggedOn.set(false);
                    Log.e(TAG, "Steam logon rejected: " + callback.getResult() + " / " + callback.getExtendedResult());
                }
                CompletableFuture<LoggedOnCallback> lf = logOnFuture;
                if (lf != null && !lf.isDone()) {
                    lf.complete(callback);
                }
            });

            callbackManager.subscribe(LoggedOffCallback.class, callback -> {
                isLoggedOn.set(false);
                Log.i(TAG, "Logged off from Steam: " + callback.getResult());
            });

            callbackManager.subscribe(LicenseListCallback.class, callback -> {
                if (callback.getResult() == EResult.OK && callback.getLicenseList() != null) {
                    ownedLicenses.clear();
                    ownedLicenses.addAll(callback.getLicenseList());
                    Log.i(TAG, "Received Steam license list: " + ownedLicenses.size() + " licenses");
                }
                CompletableFuture<List<License>> llf = licenseListFuture;
                if (llf != null && !llf.isDone()) {
                    llf.complete(new ArrayList<>(ownedLicenses));
                }
            });
        }

        private void startCallbackPump() {
            pumpRunning.set(true);
            callbackPumpThread = new Thread(() -> {
                while (pumpRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        CallbackManager mgr = this.callbackManager;
                        if (mgr != null) {
                            mgr.runWaitCallbacks(500L);
                        } else {
                            Thread.sleep(250L);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in Steam CallbackManager pump", e);
                    }
                }
            }, "Zomdroid-SteamCallbackPump");
            callbackPumpThread.setDaemon(true);
            callbackPumpThread.start();
        }

        /**
         * Авторизация по QR-коду через `SteamAuthentication.beginAuthSessionViaQR(AuthSessionDetails)`.
         * Пароль вообще не запрашивается: пользователь сканирует QR-код или подтверждает вход
         * в мобильном приложении Steam ([Longi94/JavaSteam](https://github.com/Longi94/JavaSteam)).
         */
        public void authenticateViaQr(SteamAuthCallback callback, AtomicBoolean cancelSignal) {
            try {
                ensureConnected();

                AuthSessionDetails details = new AuthSessionDetails();
                details.clientOSType = EOSType.AndroidUnknown;
                details.deviceFriendlyName = "Zomdroid Android (" + Build.MODEL + ")";
                details.persistentSession = true;

                SteamAuthentication auth = steamClient.getAuthentication();
                QrAuthSession qrSession = auth.beginAuthSessionViaQR(details).get(20L, TimeUnit.SECONDS);

                final AtomicReference<String> lastUrl = new AtomicReference<>("");
                emitQrBitmapIfChanged(qrSession.getChallengeUrl(), lastUrl, callback);

                qrSession.setChallengeUrlChanged(session -> {
                    if (session != null) {
                        emitQrBitmapIfChanged(session.getChallengeUrl(), lastUrl, callback);
                    }
                });

                CompletableFuture<AuthPollResult> pollFuture = qrSession.pollingWaitForResult();
                while (!pollFuture.isDone()) {
                    if (cancelSignal != null && cancelSignal.get()) {
                        pollFuture.cancel(true);
                        throw new CancellationException("QR authentication cancelled by user");
                    }
                    emitQrBitmapIfChanged(qrSession.getChallengeUrl(), lastUrl, callback);
                    try {
                        AuthPollResult pollResult = pollFuture.get(QR_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                        if (pollResult != null) {
                            completeInteractiveAuth(pollResult, callback);
                            return;
                        }
                    } catch (TimeoutException ignored) {
                        // Продолжаем опрос каждые 3 секунды
                    }
                }

                AuthPollResult pollResult = pollFuture.get();
                completeInteractiveAuth(pollResult, callback);
            } catch (Exception e) {
                Log.e(TAG, "QR Authentication failed", e);
                callback.onAuthError("Ошибка QR-авторизации Steam: " + e.getMessage(), e);
            }
        }

        private void emitQrBitmapIfChanged(
                String url,
                AtomicReference<String> lastUrlRef,
                SteamAuthCallback callback
        ) {
            if (url == null || url.isEmpty()) {
                return;
            }
            String prev = lastUrlRef.getAndSet(url);
            if (!url.equals(prev)) {
                try {
                    Bitmap qrBitmap = SteamQrBitmapGenerator.createQrBitmap(url, QR_BITMAP_SIZE_PX);
                    callback.onQrChallengeUrlUpdated(url, qrBitmap);
                } catch (WriterException e) {
                    Log.e(TAG, "Failed to render QR bitmap", e);
                }
            }
        }

        /**
         * Авторизация по логину и паролю с подтверждением через мобильное приложение Steam Guard
         * (`acceptDeviceConfirmation() -> true`) или код 2FA.
         * Пароль передаётся как `char[]`, используется однократно для получения `refreshToken`
         * и немедленно затирается нулями в памяти (`Arrays.fill(password, '\0')`).
         */
        public void authenticateViaCredentials(
                String username,
                char[] password,
                boolean preferMobilePushConfirmation,
                SteamAuthCallback callback
        ) {
            String transientPassword = new String(password);
            Arrays.fill(password, '\0');
            try {
                ensureConnected();

                ZomdroidAuthenticator authenticator = new ZomdroidAuthenticator(callback, preferMobilePushConfirmation);
                this.activeAuthenticator = authenticator;

                AuthSessionDetails details = new AuthSessionDetails();
                details.username = Objects.requireNonNull(username, "username").trim();
                details.password = transientPassword;
                details.clientOSType = EOSType.AndroidUnknown;
                details.deviceFriendlyName = "Zomdroid Android (" + Build.MODEL + ")";
                details.persistentSession = true;
                details.guardData = tokenStore.getGuardData();
                details.authenticator = authenticator;

                SteamAuthentication auth = steamClient.getAuthentication();
                CredentialsAuthSession session = auth.beginAuthSessionViaCredentials(details).get(20L, TimeUnit.SECONDS);

                // Очищаем ссылку на пароль из объекта запроса сразу после создания сессии
                details.password = null;
                transientPassword = null;

                AuthPollResult pollResult = session.pollingWaitForResult().get(180L, TimeUnit.SECONDS);
                completeInteractiveAuth(pollResult, callback);
            } catch (Exception e) {
                Log.e(TAG, "Credentials authentication failed", e);
                callback.onAuthError("Ошибка входа в Steam: " + e.getMessage(), e);
            } finally {
                transientPassword = null;
                this.activeAuthenticator = null;
            }
        }

        private void completeInteractiveAuth(AuthPollResult pollResult, SteamAuthCallback callback) throws Exception {
            if (pollResult == null || pollResult.getRefreshToken() == null || pollResult.getRefreshToken().isEmpty()) {
                throw new IllegalStateException("Steam не вернул refreshToken после подтверждения входа");
            }
            String accountName = pollResult.getAccountName();
            String refreshToken = pollResult.getRefreshToken();
            String newGuardData = pollResult.getNewGuardData();

            logOnWithRefreshToken(accountName, refreshToken);

            long steamId64 = steamClient.getSteamID() != null ? steamClient.getSteamID().convertToUInt64() : 0L;
            tokenStore.saveSession(
                    accountName,
                    refreshToken,
                    newGuardData,
                    steamId64,
                    tokenStore.getCellId()
            );
            callback.onAuthenticated(accountName, steamId64);
        }

        /**
         * Вход в Steam по сохранённому `refreshToken` из `EncryptedSharedPreferences` без запроса пароля.
         */
        public LoggedOnCallback logOnWithSavedSession() throws Exception {
            if (!tokenStore.hasSavedSession()) {
                throw new IllegalStateException("Нет сохранённой сессии Steam. Выполните вход по QR-коду или через Steam Guard.");
            }
            return logOnWithRefreshToken(tokenStore.getAccountName(), tokenStore.getRefreshToken());
        }

        public synchronized LoggedOnCallback logOnWithRefreshToken(String accountName, String refreshToken) throws Exception {
            ensureConnected();
            if (isLoggedOn.get()) {
                return null;
            }

            logOnFuture = new CompletableFuture<>();
            licenseListFuture = new CompletableFuture<>();

            LogOnDetails logOnDetails = new LogOnDetails();
            logOnDetails.setUsername(accountName);
            logOnDetails.setAccessToken(refreshToken);
            logOnDetails.setShouldRememberPassword(true);
            logOnDetails.setClientOSType(EOSType.AndroidUnknown);
            logOnDetails.setMachineName("Zomdroid-" + Build.MODEL);

            steamUser.logOn(logOnDetails);

            LoggedOnCallback loggedOn = logOnFuture.get(LOGIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (loggedOn.getResult() != EResult.OK) {
                throw new IOException("Не удалось войти по токену Steam: " + loggedOn.getResult()
                        + " (" + loggedOn.getExtendedResult() + ")");
            }

            // Ожидаем список лицензий аккаунта (необходим для проверки владения AppID 108600 и PICS)
            try {
                licenseListFuture.get(15L, TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
                Log.w(TAG, "LicenseListCallback timed out, continuing with available licenses");
            }
            return loggedOn;
        }

        /**
         * Анонимный вход в Steam (`SteamUser.logOnAnonymous()`), позволяющий скачивать публичные моды
         * из Steam Workshop по `PublishedFileId` без входа в личный аккаунт
         * (см. [How to download PZ game files and Workshop mods](https://www.reddit.com/r/zomdroid/comments/1rtjsj3/how_to_download_pz_game_files_and_workshop_mods/)).
         */
        public synchronized LoggedOnCallback logOnAnonymouslyIfNeeded() throws Exception {
            ensureConnected();
            if (isLoggedOn.get()) {
                return null;
            }
            if (tokenStore.hasSavedSession()) {
                try {
                    return logOnWithSavedSession();
                } catch (Exception e) {
                    Log.w(TAG, "Saved session login failed, falling back to anonymous login for Workshop", e);
                }
            }

            logOnFuture = new CompletableFuture<>();
            steamUser.logOnAnonymous();
            LoggedOnCallback loggedOn = logOnFuture.get(LOGIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (loggedOn.getResult() != EResult.OK) {
                throw new IOException("Ошибка анонимного входа в Steam: " + loggedOn.getResult());
            }
            return loggedOn;
        }

        @Override
        public synchronized void close() {
            pumpRunning.set(false);
            isLoggedOn.set(false);
            if (activeAuthenticator != null) {
                activeAuthenticator.cancelPendingCode();
                activeAuthenticator = null;
            }
            if (steamClient != null) {
                try {
                    steamClient.disconnect();
                } catch (Exception ignored) {
                }
                steamClient = null;
            }
            if (callbackPumpThread != null) {
                callbackPumpThread.interrupt();
                callbackPumpThread = null;
            }
        }
    }

    // =============================================================================================
    // 5. ИНТЕРФЕЙС ПРОГРЕССА ЗАГРУЗКИ
    // =============================================================================================

    public interface DownloadProgressListener {
        void onStageChanged(String stageDescription);

        void onProgressUpdated(
                long bytesDownloaded,
                long totalBytes,
                long speedBytesPerSec,
                String currentFileName,
                int overallPercent
        );

        void onWorkshopModInstalled(long publishedFileId, String modTitle, List<String> installedModFolders);

        void onDownloadCompleted(File instanceRootDir, File executableFile, File optionalZipFile);

        void onDownloadFailed(String errorMessage, Throwable error);
    }

    // =============================================================================================
    // 6. ЗАГРУЗЧИК ДЕПО PROJECT ZOMBOID (108600 / 108603) И МОДОВ WORKSHOP
    // =============================================================================================

    /**
     * Движок загрузки файлов Project Zomboid (`AppID = 108600`, Linux `DepotID = 108603`)
     * и модов из Steam Workshop по `PublishedFileId` (`pubfile`).
     *
     * Поддерживает:
     * - Выбор ветки `public` (Build 41) или `unstable` (Build 42).
     * - Указание конкретного `manifestId` (например, `7735218517840308728` для Build 42.7)
     *   или автоматическое получение последнего манифеста ветки через протокол PICS.
     * - Работу как через прямое управление PICS + `SteamContent` + `CDN.Client` + `DepotManifest` + `DepotChunk`
     *   (для точного пиннинга `manifestId` и `DepotID = 108603`), так и через `DepotDownloader`
     *   из пакета `in.dragonbra:javasteam-depotdownloader:1.8.0`.
     */
    public static final class ZomboidContentDownloader {
        private final SteamSessionManager sessionManager;
        private final ConcurrentHashMap<Integer, byte[]> depotKeyCache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CDNAuthToken> cdnTokenCache = new ConcurrentHashMap<>();

        public ZomboidContentDownloader(SteamSessionManager sessionManager) {
            this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        }

        /**
         * Полный цикл загрузки файлов игры Project Zomboid и/или списка модов Steam Workshop.
         */
        public void executeDownload(
                DownloadRequest request,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) throws Exception {
            checkCancelled(cancelSignal);

            boolean needGameDownload = request.getDepotId() > 0 && request.getBranch() != null && !request.getBranch().isEmpty();
            if (needGameDownload) {
                listener.onStageChanged("Авторизация в Steam и проверка лицензии Project Zomboid...");
                sessionManager.logOnWithSavedSession();

                if (request.isUseHighLevelDepotDownloader()
                        && request.getManifestId() == ZomboidSteamConfig.MANIFEST_LATEST) {
                    downloadGameViaHighLevelDepotDownloader(request, listener, cancelSignal);
                } else {
                    downloadGameDepotDirect(request, listener, cancelSignal);
                }

                listener.onStageChanged("Настройка прав запуска ProjectZomboid64 и нормализация директорий...");
                File executable = ZomboidPostInstallProcessor.normalizeAndSetExecutable(request.getTargetInstanceDir());

                File exportedZip = null;
                if (request.isExportInstanceZip()) {
                    File targetZip = request.getOptionalOutputZipFile() != null
                            ? request.getOptionalOutputZipFile()
                            : new File(
                                    request.getTargetInstanceDir().getParentFile(),
                                    "ProjectZomboid_" + request.getBranch() + "_"
                                            + (request.getManifestId() > 0 ? request.getManifestId() : "latest") + ".zip"
                            );
                    listener.onStageChanged("Упаковка файлов игры в ZIP-архив для Zomdroid...");
                    ZomboidPostInstallProcessor.packInstanceRootToZip(
                            request.getTargetInstanceDir(),
                            targetZip,
                            cancelSignal
                    );
                    exportedZip = targetZip;
                }

                if (!request.getWorkshopPublishedFileIds().isEmpty()) {
                    downloadAndInstallWorkshopMods(request, listener, cancelSignal);
                }

                listener.onDownloadCompleted(request.getTargetInstanceDir(), executable, exportedZip);
            } else if (!request.getWorkshopPublishedFileIds().isEmpty()) {
                listener.onStageChanged("Подключение к Steam для загрузки модов Workshop...");
                sessionManager.logOnAnonymouslyIfNeeded();
                downloadAndInstallWorkshopMods(request, listener, cancelSignal);
                File execCandidate = new File(request.getTargetInstanceDir(), ZomboidSteamConfig.MAIN_EXECUTABLE);
                listener.onDownloadCompleted(request.getTargetInstanceDir(), execCandidate, null);
            }
        }

        /**
         * Высокоуровневая загрузка через `in.dragonbra.javasteam.depotdownloader.DepotDownloader`
         * (см. [Longi94/JavaSteam Releases v1.8.0](https://github.com/Longi94/JavaSteam/releases)
         * и [STEAM_IMPLEMENTATION.md](https://github.com/The412Banner/BannerHub/blob/main/game-store-reports/STEAM_IMPLEMENTATION.md)).
         */
        private void downloadGameViaHighLevelDepotDownloader(
                DownloadRequest request,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) throws Exception {
            SteamClient client = sessionManager.getSteamClient();
            List<License> licenses = sessionManager.getOwnedLicenses();

            try (DepotDownloader downloader = new DepotDownloader(
                    client,
                    licenses,
                    false,
                    false, // androidEmulation = false, так как для Zomdroid нужен нативный Linux-депо 108603
                    request.getMaxParallelChunks(),
                    request.getMaxParallelChunks(),
                    false
            )) {
                final AtomicLong startTimeMs = new AtomicLong(System.currentTimeMillis());
                downloader.addListener(new IDownloadListener() {
                    @Override
                    public void onStatusUpdated(String message) {
                        listener.onStageChanged(message);
                    }

                    @Override
                    public void onFileCompleted(int depotId, String fileName, float depotPercentComplete) {
                        int pct = Math.min(100, Math.max(0, Math.round(depotPercentComplete * 100f)));
                        listener.onProgressUpdated(0L, 0L, 0L, fileName, pct);
                    }

                    @Override
                    public void onDepotCompleted(int depotId, long compressedBytes, long uncompressedBytes) {
                        long elapsedSec = Math.max(1L, (System.currentTimeMillis() - startTimeMs.get()) / 1000L);
                        long speed = compressedBytes / elapsedSec;
                        listener.onProgressUpdated(
                                uncompressedBytes,
                                uncompressedBytes,
                                speed,
                                "Depot " + depotId + " завершён",
                                100
                        );
                    }

                    @Override
                    public void onDownloadCompleted(DownloadItem item) {
                        Log.i(TAG, "High-level DepotDownloader finished item: " + item);
                    }

                    @Override
                    public void onDownloadFailed(DownloadItem item, Throwable error) {
                        Log.e(TAG, "High-level DepotDownloader error on item: " + item, error);
                    }
                });

                AppItem appItem = new AppItem(
                        request.getAppId(),
                        false,
                        request.getTargetInstanceDir().getAbsolutePath(),
                        request.getBranch(),
                        request.getBranchPassword() != null ? request.getBranchPassword() : "",
                        false,
                        "linux",
                        "64",
                        false,
                        false,
                        new ArrayList<>(Collections.singletonList(request.getDepotId())),
                        new ArrayList<>()
                );

                downloader.add(appItem);
                downloader.finishAdding();
                downloader.awaitCompletion();
            }
        }

        /**
         * Прямая загрузка Linux-депо `108603` через PICS + `SteamContent` + `CDN.Client` + `DepotManifest`.
         * Поддерживает как автоматическое разрешение последнего манифеста ветки (`public` / `unstable`),
         * так и жёсткую фиксацию `manifestId` (например, `7735218517840308728` для Build 42.7).
         */
        private void downloadGameDepotDirect(
                DownloadRequest request,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) throws Exception {
            int appId = request.getAppId();
            int depotId = request.getDepotId();
            String branch = request.getBranch();
            long requestedManifestId = request.getManifestId();

            listener.onStageChanged("Запрос метаданных PICS для AppID " + appId + " (ветка " + branch + ")...");
            KeyValue appKeyValues = fetchAppPicsKeyValues(appId);

            long manifestId = requestedManifestId;
            if (manifestId <= 0) {
                manifestId = resolveBranchManifestIdFromPics(appKeyValues, depotId, branch, request.getBranchPassword());
                if (manifestId <= 0) {
                    throw new IOException("Не удалось найти manifestId для депо " + depotId
                            + " и ветки '" + branch + "'. Проверьте, что ветка выбрана в свойствах Steam.");
                }
            }

            Log.i(TAG, "Resolved Project Zomboid depot " + depotId + " branch=" + branch + " manifestId=" + manifestId);
            listener.onStageChanged("Получение ключа расшифровки депо " + depotId + "...");
            byte[] depotKey = fetchDepotDecryptionKey(depotId, appId);

            downloadSpecificDepotManifest(
                    appId,
                    depotId,
                    manifestId,
                    branch,
                    request.getBranchPassword(),
                    depotKey,
                    request.getTargetInstanceDir(),
                    request.getMaxParallelChunks(),
                    listener,
                    cancelSignal
            );
        }

        /**
         * Скачивание и раскладка модов из Steam Workshop по списку `PublishedFileId` (`pubfile`)
         * в директорию `Zomboid/mods/`.
         * См. [How to download PZ game files and Workshop mods](https://www.reddit.com/r/zomdroid/comments/1rtjsj3/how_to_download_pz_game_files_and_workshop_mods/).
         */
        public void downloadAndInstallWorkshopMods(
                DownloadRequest request,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) throws Exception {
            List<Long> pubFileIds = request.getWorkshopPublishedFileIds();
            if (pubFileIds.isEmpty()) {
                return;
            }

            File modsDestinationDir = request.getModsDir();
            if (!modsDestinationDir.exists() && !modsDestinationDir.mkdirs()) {
                throw new IOException("Не удалось создать папку модов: " + modsDestinationDir.getAbsolutePath());
            }

            File stagingRoot = new File(request.getTargetInstanceDir(), ".workshop_staging");
            if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
                throw new IOException("Не удалось создать временную папку Workshop: " + stagingRoot.getAbsolutePath());
            }

            listener.onStageChanged("Запрос метаданных Steam Workshop для " + pubFileIds.size() + " модов...");
            List<PublishedFileDetails> detailsList = fetchPublishedFileDetails(pubFileIds);

            for (PublishedFileDetails details : detailsList) {
                checkCancelled(cancelSignal);
                long pubFileId = details.getPublishedfileid();
                String title = details.hasTitle() ? details.getTitle() : ("Workshop_" + pubFileId);

                if (details.getResult() != EResult.OK.code()) {
                    throw new IOException("Мод Workshop " + pubFileId + " недоступен (EResult=" + details.getResult() + ")");
                }

                File modStagingDir = new File(stagingRoot, String.valueOf(pubFileId));
                deleteDirectoryRecursively(modStagingDir);
                if (!modStagingDir.mkdirs()) {
                    throw new IOException("Не удалось подготовить директорию для мода " + pubFileId);
                }

                listener.onStageChanged("Загрузка мода Workshop: " + title + " (" + pubFileId + ")...");

                long hcontentFile = details.getHcontentFile();
                String directFileUrl = details.getFileUrl();

                if (directFileUrl != null && !directFileUrl.isEmpty()) {
                    // Способ 1: Прямой URL файла UGC, если предоставлен Steam
                    File downloadedFile = new File(modStagingDir, sanitizeFileName( details.getFilename(), pubFileId + ".bin"));
                    downloadDirectHttpUrl(directFileUrl, downloadedFile, listener, cancelSignal);
                    extractIfArchive(downloadedFile, modStagingDir);
                } else if (hcontentFile != 0L) {
                    // Способ 2: В современных играх SteamPipe (включая Project Zomboid AppID 108600)
                    // hcontent_file является manifestId в Workshop-депо с ID = consumer_appid (108600).
                    // Если запрос кода манифеста не удался, пробуем устаревший SteamCloud UGC.
                    boolean downloadedAsDepot = tryDownloadWorkshopAsDepotManifest(
                            request.getAppId(),
                            hcontentFile,
                            modStagingDir,
                            request.getMaxParallelChunks(),
                            listener,
                            cancelSignal
                    );
                    if (!downloadedAsDepot) {
                        downloadLegacyUgcFile(hcontentFile, modStagingDir, listener, cancelSignal);
                    }
                } else if (request.isUseHighLevelDepotDownloader()) {
                    // Способ 3: Использование PubFileItem из javasteam-depotdownloader
                    try (DepotDownloader downloader = new DepotDownloader(
                            sessionManager.getSteamClient(),
                            sessionManager.getOwnedLicenses(),
                            false,
                            false,
                            request.getMaxParallelChunks(),
                            request.getMaxParallelChunks(),
                            false
                    )) {
                        PubFileItem pubItem = new PubFileItem(
                                request.getAppId(),
                                false,
                                modStagingDir.getAbsolutePath(),
                                pubFileId
                        );
                        downloader.add(pubItem);
                        downloader.finishAdding();
                        downloader.awaitCompletion();
                    }
                } else {
                    throw new IOException("У мода Workshop " + pubFileId + " (" + title + ") отсутствует hcontent_file и file_url");
                }

                // Раскладываем скачанный мод в <zomboidUserDir>/Zomboid/mods/<ModFolderName>/
                List<String> installedFolders = WorkshopModInstaller.installFromStagingToZomboidMods(
                        modStagingDir,
                        modsDestinationDir,
                        pubFileId,
                        title
                );
                deleteDirectoryRecursively(modStagingDir);

                listener.onWorkshopModInstalled(pubFileId, title, installedFolders);
            }

            deleteDirectoryRecursively(stagingRoot);
        }

        private boolean tryDownloadWorkshopAsDepotManifest(
                int appId,
                long manifestId,
                File targetDir,
                int maxThreads,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) {
            try {
                // Для SteamPipe Workshop депо имеет ID, равный AppID (108600 для Project Zomboid)
                byte[] depotKey = null;
                try {
                    depotKey = fetchDepotDecryptionKey(appId, appId);
                } catch (Exception ignored) {
                    // Публичные моды Workshop могут быть незашифрованы или использовать ключ депо 108600
                }
                downloadSpecificDepotManifest(
                        appId,
                        appId,
                        manifestId,
                        ZomboidSteamConfig.BRANCH_PUBLIC,
                        null,
                        depotKey,
                        targetDir,
                        maxThreads,
                        listener,
                        cancelSignal
                );
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Workshop item manifest " + manifestId + " is not a standard depot manifest, trying UGC", e);
                return false;
            }
        }

        private void downloadLegacyUgcFile(
                long ugcId,
                File targetDir,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) throws Exception {
            SteamCloud cloud = sessionManager.getSteamCloud();
            if (cloud == null) {
                throw new IllegalStateException("SteamCloud handler is not initialized");
            }
            UGCDetailsCallback ugcDetails = cloud.requestUGCDetails(new UGCHandle(ugcId)).toFuture().get(20L, TimeUnit.SECONDS);
            if (ugcDetails.getResult() != EResult.OK || ugcDetails.getUrl() == null || ugcDetails.getUrl().isEmpty()) {
                throw new IOException("Не удалось получить UGC URL для handle=" + ugcId + ": " + ugcDetails.getResult());
            }
            File outFile = new File(targetDir, sanitizeFileName(ugcDetails.getFileName(), ugcId + ".ugc"));
            downloadDirectHttpUrl(ugcDetails.getUrl(), outFile, listener, cancelSignal);
            extractIfArchive(outFile, targetDir);
        }

        private List<PublishedFileDetails> fetchPublishedFileDetails(List<Long> publishedFileIds) throws Exception {
            PublishedFile publishedFile = sessionManager.getPublishedFileService();
            if (publishedFile == null) {
                throw new IllegalStateException("PublishedFile Unified Service is not available");
            }

            CPublishedFile_GetDetails_Request.Builder reqBuilder = CPublishedFile_GetDetails_Request.newBuilder();
            reqBuilder.setIncludechildren(true);
            reqBuilder.setIncludemetadata(true);
            for (Long id : publishedFileIds) {
                if (id != null && id > 0) {
                    reqBuilder.addPublishedfileids(id);
                }
            }

            ServiceMethodResponse<CPublishedFile_GetDetails_Response.Builder> response =
                    publishedFile.getDetails(reqBuilder.build()).toFuture().get(25L, TimeUnit.SECONDS);

            if (response.getResult() != EResult.OK) {
                throw new IOException("Ошибка запроса PublishedFile.GetDetails: " + response.getResult());
            }
            return response.getBody().getPublishedfiledetailsList();
        }

        private KeyValue fetchAppPicsKeyValues(int appId) throws Exception {
            SteamApps apps = sessionManager.getSteamApps();
            PICSTokensCallback tokensCallback = apps.picsGetAccessTokens(
                    Collections.singletonList(appId),
                    Collections.emptyList()
            ).toFuture().get(20L, TimeUnit.SECONDS);

            long accessToken = 0L;
            if (tokensCallback.getAppTokens() != null && tokensCallback.getAppTokens().containsKey(appId)) {
                Long tokenObj = tokensCallback.getAppTokens().get(appId);
                if (tokenObj != null) {
                    accessToken = tokenObj;
                }
            }

            PICSRequest picsRequest = new PICSRequest(appId, accessToken);
            PICSProductInfoCallback.PICSProductInfoResultSet resultSet = apps.picsGetProductInfo(
                    Collections.singletonList(picsRequest),
                    Collections.emptyList()
            ).toFuture().get(30L, TimeUnit.SECONDS);

            if (resultSet.getResults() != null) {
                for (PICSProductInfoCallback callback : resultSet.getResults()) {
                    if (callback.getApps() != null && callback.getApps().containsKey(appId)) {
                        PICSProductInfoCallback.PICSProductInfo appInfo = callback.getApps().get(appId);
                        if (appInfo != null && appInfo.getKeyValues() != null) {
                            return appInfo.getKeyValues();
                        }
                    }
                }
            }
            throw new IOException("PICS не вернул информацию для AppID " + appId);
        }

        private long resolveBranchManifestIdFromPics(
                KeyValue appKv,
                int depotId,
                String branch,
                String branchPassword
        ) {
            KeyValue depotsKv = appKv.get("depots");
            if (depotsKv == KeyValue.INVALID) {
                return ZomboidSteamConfig.MANIFEST_LATEST;
            }
            KeyValue specificDepotKv = depotsKv.get(String.valueOf(depotId));
            if (specificDepotKv == KeyValue.INVALID) {
                return ZomboidSteamConfig.MANIFEST_LATEST;
            }
            KeyValue manifestsKv = specificDepotKv.get("manifests");
            if (manifestsKv != KeyValue.INVALID) {
                KeyValue branchKv = manifestsKv.get(branch);
                if (branchKv != KeyValue.INVALID) {
                    KeyValue gidKv = branchKv.get("gid");
                    String rawValue = (gidKv != KeyValue.INVALID && gidKv.getValue() != null)
                            ? gidKv.getValue()
                            : branchKv.getValue();
                    if (rawValue != null && !rawValue.isEmpty()) {
                        try {
                            return Long.parseUnsignedLong(rawValue.trim());
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Failed to parse manifest GID: " + rawValue, e);
                        }
                    }
                }
            }
            return ZomboidSteamConfig.MANIFEST_LATEST;
        }

        private byte[] fetchDepotDecryptionKey(int depotId, int appId) throws Exception {
            byte[] cached = depotKeyCache.get(depotId);
            if (cached != null) {
                return cached;
            }
            DepotKeyCallback keyCallback = sessionManager.getSteamApps()
                    .getDepotDecryptionKey(depotId, appId)
                    .toFuture()
                    .get(20L, TimeUnit.SECONDS);

            if (keyCallback.getResult() != EResult.OK || keyCallback.getDepotKey() == null) {
                throw new IOException("Не удалось получить ключ расшифровки для депо " + depotId
                        + " (EResult=" + keyCallback.getResult() + "). Убедитесь, что игра куплена на аккаунте Steam.");
            }
            byte[] key = keyCallback.getDepotKey();
            depotKeyCache.put(depotId, key);
            return key;
        }

        /**
         * Полная загрузка манифеста и всех чанков депо через `SteamContent` и `in.dragonbra.javasteam.steam.cdn.Client`.
         */
        private void downloadSpecificDepotManifest(
                int appId,
                int depotId,
                long manifestId,
                String branch,
                String branchPassword,
                byte[] depotKey,
                File installDir,
                int maxThreads,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) throws Exception {
            checkCancelled(cancelSignal);
            SteamContent content = sessionManager.getSteamContent();

            listener.onStageChanged("Получение кода запроса манифеста " + manifestId + " (депо " + depotId + ")...");
            long manifestRequestCode = content.getManifestRequestCode(
                    depotId,
                    appId,
                    manifestId,
                    branch,
                    branchPassword
            ).get(25L, TimeUnit.SECONDS);

            listener.onStageChanged("Поиск ближайших CDN-серверов SteamPipe...");
            int cellId = sessionManager.getTokenStore().getCellId();
            Collection<Server> servers = content.getServersForSteamPipe(cellId, 20).get(25L, TimeUnit.SECONDS);
            if (servers == null || servers.isEmpty()) {
                throw new IOException("SteamPipe не вернул ни одного доступного CDN-сервера");
            }
            List<Server> serverPool = new ArrayList<>(servers);

            try (Client cdnClient = new Client(sessionManager.getSteamClient())) {
                Server primaryServer = serverPool.get(0);
                String cdnToken = fetchCdnAuthTokenIfNeeded(content, appId, depotId, primaryServer);

                listener.onStageChanged("Скачивание и расшифровка манифеста " + manifestId + "...");
                DepotManifest manifest = cdnClient.downloadManifest(
                        depotId,
                        manifestId,
                        manifestRequestCode,
                        primaryServer,
                        depotKey,
                        null,
                        cdnToken
                );

                if (manifest == null || manifest.getFiles() == null) {
                    throw new IOException("Манифест " + manifestId + " пуст или не может быть прочитан");
                }

                List<FileData> files = manifest.getFiles();
                long totalUncompressedBytes = 0L;
                for (FileData f : files) {
                    if (!f.getFlags().contains(in.dragonbra.javasteam.enums.EDepotFileFlag.Directory)) {
                        totalUncompressedBytes += f.getTotalSize();
                    }
                }

                final long finalTotalBytes = Math.max(1L, totalUncompressedBytes);
                final AtomicLong downloadedBytes = new AtomicLong(0L);
                final long startTimestampMs = System.currentTimeMillis();

                ExecutorService chunkPool = Executors.newFixedThreadPool(maxThreads);
                try {
                    for (FileData fileData : files) {
                        checkCancelled(cancelSignal);
                        String relativePath = fileData.getFileName().replace('\\', File.separatorChar);
                        File targetFile = new File(installDir, relativePath);

                        if (fileData.getFlags().contains(in.dragonbra.javasteam.enums.EDepotFileFlag.Directory)) {
                            if (!targetFile.exists() && !targetFile.mkdirs()) {
                                Log.w(TAG, "Could not create directory: " + targetFile.getAbsolutePath());
                            }
                            continue;
                        }

                        File parent = targetFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IOException("Не удалось создать директорию: " + parent.getAbsolutePath());
                        }

                        // Проверка целостности уже существующего файла (поддержка докачки / Resume)
                        if (targetFile.exists() && targetFile.length() == fileData.getTotalSize()
                                && verifyFileSha1(targetFile, fileData.getFileHash())) {
                            long current = downloadedBytes.addAndGet(fileData.getTotalSize());
                            int pct = (int) Math.min(100L, (current * 100L) / finalTotalBytes);
                            listener.onProgressUpdated(current, finalTotalBytes, 0L, relativePath, pct);
                            continue;
                        }

                        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
                            raf.setLength(fileData.getTotalSize());
                        }

                        List<ChunkData> chunks = fileData.getChunks();
                        List<Future<?>> chunkFutures = new ArrayList<>(chunks.size());

                        for (int i = 0; i < chunks.size(); i++) {
                            final ChunkData chunk = chunks.get(i);
                            final int serverIndex = i % serverPool.size();
                            chunkFutures.add(chunkPool.submit(() -> {
                                checkCancelled(cancelSignal);
                                downloadAndWriteChunkWithRetry(
                                        cdnClient,
                                        content,
                                        appId,
                                        depotId,
                                        chunk,
                                        depotKey,
                                        serverPool,
                                        serverIndex,
                                        targetFile
                                );
                                long done = downloadedBytes.addAndGet(chunk.getUncompressedLength());
                                long elapsedMs = Math.max(1L, System.currentTimeMillis() - startTimestampMs);
                                long speedBps = (done * 1000L) / elapsedMs;
                                int pct = (int) Math.min(100L, (done * 100L) / finalTotalBytes);
                                listener.onProgressUpdated(done, finalTotalBytes, speedBps, relativePath, pct);
                                return null;
                            }));
                        }

                        for (Future<?> f : chunkFutures) {
                            try {
                                f.get();
                            } catch (ExecutionException ee) {
                                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                                if (cause instanceof Exception) {
                                    throw (Exception) cause;
                                }
                                throw new IOException("Ошибка загрузки чанка файла " + relativePath, cause);
                            }
                        }

                        // Если манифест пометил файл как исполняемый, сразу выставляем флаг
                        if (fileData.getFlags().contains(in.dragonbra.javasteam.enums.EDepotFileFlag.Executable)) {
                            ZomboidPostInstallProcessor.makeFileExecutable(targetFile);
                        }
                    }
                } finally {
                    chunkPool.shutdownNow();
                }
            }
        }

        private void downloadAndWriteChunkWithRetry(
                Client cdnClient,
                SteamContent content,
                int appId,
                int depotId,
                ChunkData chunk,
                byte[] depotKey,
                List<Server> serverPool,
                int initialServerIndex,
                File targetFile
        ) throws Exception {
            int maxAttempts = Math.min(4, Math.max(2, serverPool.size()));
            Exception lastException = null;

            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                Server server = serverPool.get((initialServerIndex + attempt) % serverPool.size());
                try {
                    String cdnToken = fetchCdnAuthTokenIfNeeded(content, appId, depotId, server);
                    byte[] encryptedBuffer = new byte[chunk.getCompressedLength()];
                    int readBytes = cdnClient.downloadDepotChunk(
                            depotId,
                            chunk,
                            server,
                            encryptedBuffer,
                            null,
                            cdnToken
                    );
                    if (readBytes <= 0) {
                        throw new IOException("CDN вернул 0 байт для чанка " + bytesToHex(chunk.getChunkID()));
                    }

                    byte[] uncompressedData = new byte[chunk.getUncompressedLength()];
                    int writtenBytes = DepotChunk.process(
                            chunk,
                            encryptedBuffer,
                            uncompressedData,
                            depotKey
                    );

                    synchronized (targetFile.getAbsolutePath().intern()) {
                        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
                            raf.seek(chunk.getOffset());
                            raf.write(uncompressedData, 0, writtenBytes);
                        }
                    }
                    return;
                } catch (Exception e) {
                    lastException = e;
                    Log.w(TAG, "Chunk download retry " + (attempt + 1) + "/" + maxAttempts
                            + " on server " + server.getHost() + ": " + e.getMessage());
                }
            }
            throw new IOException("Не удалось скачать чанк после " + maxAttempts + " попыток", lastException);
        }

        private String fetchCdnAuthTokenIfNeeded(
                SteamContent content,
                int appId,
                int depotId,
                Server server
        ) {
            String host = server.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            String cacheKey = depotId + ":" + host;
            CDNAuthToken cached = cdnTokenCache.get(cacheKey);
            if (cached != null) {
                return cached.getToken();
            }
            try {
                CDNAuthToken token = content.getCDNAuthToken(appId, depotId, host).get(10L, TimeUnit.SECONDS);
                if (token != null && token.getResult() == EResult.OK) {
                    cdnTokenCache.put(cacheKey, token);
                    return token.getToken();
                }
            } catch (Exception e) {
                Log.d(TAG, "CDN auth token not required or failed for host " + host + ": " + e.getMessage());
            }
            return null;
        }

        private static void downloadDirectHttpUrl(
                String fileUrl,
                File destinationFile,
                DownloadProgressListener listener,
                AtomicBoolean cancelSignal
        ) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(destinationFile)) {
                long total = Math.max(1L, conn.getContentLengthLong());
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0L;
                long startMs = System.currentTimeMillis();
                int n;
                while ((n = in.read(buffer)) != -1) {
                    checkCancelled(cancelSignal);
                    out.write(buffer, 0, n);
                    downloaded += n;
                    long elapsed = Math.max(1L, System.currentTimeMillis() - startMs);
                    long speed = (downloaded * 1000L) / elapsed;
                    int pct = (int) Math.min(100L, (downloaded * 100L) / total);
                    listener.onProgressUpdated(downloaded, total, speed, destinationFile.getName(), pct);
                }
            } finally {
                conn.disconnect();
            }
        }

        private static void extractIfArchive(File file, File destDir) throws IOException {
            if (!file.exists() || file.length() < 4) {
                return;
            }
            byte[] magic = new byte[4];
            try (FileInputStream fis = new FileInputStream(file)) {
                if (fis.read(magic) != 4) {
                    return;
                }
            }
            // Проверка сигнатуры PK\x03\x04 (ZIP-архив)
            if (magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04) {
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                    ZipEntry entry;
                    byte[] buffer = new byte[64 * 1024];
                    String canonicalDest = destDir.getCanonicalPath() + File.separator;
                    while ((entry = zis.getNextEntry()) != null) {
                        File out = new File(destDir, entry.getName());
                        if (!out.getCanonicalPath().startsWith(canonicalDest)) {
                            throw new SecurityException("Zip Slip обнаружен: " + entry.getName());
                        }
                        if (entry.isDirectory()) {
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) {
                                parent.mkdirs();
                            }
                            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                                int len;
                                while ((len = zis.read(buffer)) > 0) {
                                    bos.write(buffer, 0, len);
                                }
                            }
                        }
                        zis.closeEntry();
                    }
                }
                file.delete();
            }
        }

        private static boolean verifyFileSha1(File file, byte[] expectedSha1) {
            if (expectedSha1 == null || expectedSha1.length == 0) {
                return false;
            }
            boolean allZero = true;
            for (byte b : expectedSha1) {
                if (b != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                return true;
            }
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        sha1.update(buf, 0, r);
                    }
                }
                return MessageDigest.isEqual(sha1.digest(), expectedSha1);
            } catch (Exception e) {
                return false;
            }
        }

        private static void checkCancelled(AtomicBoolean cancelSignal) {
            if (cancelSignal != null && cancelSignal.get()) {
                throw new CancellationException("Загрузка отменена пользователем");
            }
        }

        private static String sanitizeFileName(String name, String fallback) {
            if (name == null || name.trim().isEmpty()) {
                return fallback;
            }
            return name.replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        private static String bytesToHex(byte[] bytes) {
            if (bytes == null) {
                return "null";
            }
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        }
    }

    // =============================================================================================
    // 7. УСТАНОВЩИК МОДОВ WORKSHOP В ПАПКУ Zomboid/mods/ И ПОСТ-ОБРАБОТКА PROJECTZOMBOID64
    // =============================================================================================

    /**
     * Установщик модов Project Zomboid из временной папки Workshop в целевую директорию `Zomboid/mods/`.
     *
     * В Steam Workshop моды для Project Zomboid обычно имеют структуру:
     * `<WorkshopItemRoot>/mods/<ModFolderName>/mod.info` (и иногда содержат сразу несколько под-модов).
     * Класс рекурсивно находит все папки, содержащие `mod.info`, и копирует каждую такую папку
     * напрямую в `Zomboid/mods/<ModFolderName>/`, чтобы избегать ошибки двойной вложенности путей
     * в Zomdroid.
     */
    public static final class WorkshopModInstaller {
        private WorkshopModInstaller() {
        }

        public static List<String> installFromStagingToZomboidMods(
                File stagingDir,
                File zomboidModsDir,
                long publishedFileId,
                String workshopTitle
        ) throws IOException {
            if (!zomboidModsDir.exists() && !zomboidModsDir.mkdirs()) {
                throw new IOException("Не удалось создать директорию модов: " + zomboidModsDir.getAbsolutePath());
            }

            List<File> modInfoFiles = new ArrayList<>();
            findModInfoFilesRecursively(stagingDir, modInfoFiles);

            List<String> installedFolders = new ArrayList<>();

            if (!modInfoFiles.isEmpty()) {
                // Для каждого найденного mod.info определяем корневую папку мода
                Set<String> processedModRoots = new HashSet<>();
                for (File modInfo : modInfoFiles) {
                    File modRoot = resolveModRootDirectory(stagingDir, modInfo);
                    String canonicalPath = modRoot.getCanonicalPath();
                    if (!processedModRoots.add(canonicalPath)) {
                        continue;
                    }
                    String folderName = modRoot.getName();
                    if (folderName.equalsIgnoreCase("common") || folderName.equalsIgnoreCase("42")) {
                        File parent = modRoot.getParentFile();
                        if (parent != null && !parent.equals(stagingDir) && !parent.getName().equalsIgnoreCase("mods")) {
                            modRoot = parent;
                            folderName = parent.getName();
                        }
                    }

                    File targetModDir = new File(zomboidModsDir, folderName);
                    deleteDirectoryRecursively(targetModDir);
                    copyDirectoryRecursively(modRoot, targetModDir);
                    installedFolders.add(folderName);
                    Log.i(TAG, "Installed Workshop mod '" + folderName + "' (pubfile=" + publishedFileId
                            + ") to " + targetModDir.getAbsolutePath());
                }
            } else {
                // Если mod.info находится глубже или структура нестандартная, проверяем подпапку mods/
                File modsSubdir = new File(stagingDir, "mods");
                if (modsSubdir.exists() && modsSubdir.isDirectory()) {
                    File[] children = modsSubdir.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            if (child.isDirectory()) {
                                File targetModDir = new File(zomboidModsDir, child.getName());
                                deleteDirectoryRecursively(targetModDir);
                                copyDirectoryRecursively(child, targetModDir);
                                installedFolders.add(child.getName());
                            }
                        }
                    }
                }
                if (installedFolders.isEmpty()) {
                    String fallbackName = sanitizeModFolderName(workshopTitle, publishedFileId);
                    File targetModDir = new File(zomboidModsDir, fallbackName);
                    deleteDirectoryRecursively(targetModDir);
                    copyDirectoryRecursively(stagingDir, targetModDir);
                    installedFolders.add(fallbackName);
                }
            }
            return installedFolders;
        }

        private static void findModInfoFilesRecursively(File dir, List<File> outList) {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.isDirectory()) {
                    findModInfoFilesRecursively(f, outList);
                } else if (f.getName().equalsIgnoreCase("mod.info")) {
                    outList.add(f);
                }
            }
        }

        /**
         * В Build 41 `mod.info` лежит прямо в `mods/<ModName>/mod.info`,
         * а в Build 42 часто лежит в `mods/<ModName>/42/mod.info` или `mods/<ModName>/common/mod.info`.
         * Поднимаемся до непосредственного потомка папки `mods/`.
         */
        private static File resolveModRootDirectory(File stagingRoot, File modInfoFile) {
            File current = modInfoFile.getParentFile();
            while (current != null && !current.equals(stagingRoot)) {
                File parent = current.getParentFile();
                if (parent != null && parent.getName().equalsIgnoreCase("mods")) {
                    return current;
                }
                current = parent;
            }
            return modInfoFile.getParentFile() != null ? modInfoFile.getParentFile() : stagingRoot;
        }

        private static String sanitizeModFolderName(String title, long pubFileId) {
            if (title != null && !title.trim().isEmpty()) {
                String clean = title.trim().replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
                if (!clean.isEmpty()) {
                    return clean;
                }
            }
            return "WorkshopMod_" + pubFileId;
        }
    }

    /**
     * Пост-обработчик скачанных файлов Linux-депо `108603`:
     * 1) Поднимает содержимое подпапки `projectzomboid/` в корень инстанса, чтобы `ProjectZomboid64`
     *    находился на верхнем уровне (исправляет частую ошибку Zomdroid `ProjectZomboid64 is not on top`).
     * 2) Устанавливает флаг исполняемости `setExecutable(true, false)` и `chmod 0755` для `ProjectZomboid64`,
     *    `projectzomboid.sh` и всех бинарных утилит `jre64/bin/*` и `.so`-библиотек.
     */
    public static final class ZomboidPostInstallProcessor {
        private ZomboidPostInstallProcessor() {
        }

        public static File normalizeAndSetExecutable(File instanceDir) throws IOException {
            if (!instanceDir.exists()) {
                throw new IOException("Директория инстанса не существует: " + instanceDir.getAbsolutePath());
            }

            // В депо 108603 файлы лежат внутри подпапки projectzomboid/ (см. SteamDB Depot 108603)
            File nestedPzDir = new File(instanceDir, ZomboidSteamConfig.DEPOT_ROOT_SUBFOLDER);
            File rootBinary = new File(instanceDir, ZomboidSteamConfig.MAIN_EXECUTABLE);
            File nestedBinary = new File(nestedPzDir, ZomboidSteamConfig.MAIN_EXECUTABLE);

            if (!rootBinary.exists() && nestedBinary.exists() && nestedPzDir.isDirectory()) {
                moveContentsUpToParent(nestedPzDir, instanceDir);
            }

            if (!rootBinary.exists()) {
                // Поиск ProjectZomboid64 на случай, если DepotDownloader создал depots/108603/.../projectzomboid
                File locatedBinary = findFileByNameRecursively(instanceDir, ZomboidSteamConfig.MAIN_EXECUTABLE);
                if (locatedBinary != null && locatedBinary.getParentFile() != null
                        && !locatedBinary.getParentFile().equals(instanceDir)) {
                    moveContentsUpToParent(locatedBinary.getParentFile(), instanceDir);
                }
            }

            if (!rootBinary.exists()) {
                throw new IOException("Исполняемый файл " + ZomboidSteamConfig.MAIN_EXECUTABLE
                        + " не найден после загрузки в " + instanceDir.getAbsolutePath());
            }

            // Гарантированная установка флага исполняемости для ProjectZomboid64
            makeFileExecutable(rootBinary);

            File launchScript = new File(instanceDir, ZomboidSteamConfig.LAUNCH_SCRIPT);
            if (launchScript.exists()) {
                makeFileExecutable(launchScript);
            }

            // Делаем исполняемыми бинарники встроенной JRE (jre64/bin/* и jre64/lib/jspawnhelper) и .so библиотеки
            File jre64Dir = new File(instanceDir, "jre64");
            if (jre64Dir.exists()) {
                applyExecutablePermissionsRecursively(jre64Dir);
            }
            File linux64Dir = new File(instanceDir, "linux64");
            if (linux64Dir.exists()) {
                applyExecutablePermissionsRecursively(linux64Dir);
            }

            File[] topLevelFiles = instanceDir.listFiles();
            if (topLevelFiles != null) {
                for (File f : topLevelFiles) {
                    if (f.isFile() && (f.getName().endsWith(".so") || f.getName().contains(".so.") || f.getName().endsWith(".sh"))) {
                        makeFileExecutable(f);
                    }
                }
            }

            return rootBinary;
        }

        public static void makeFileExecutable(File file) {
            if (file == null || !file.exists()) {
                return;
            }
            boolean readableOk = file.setReadable(true, false);
            boolean execOk = file.setExecutable(true, false);
            try {
                Os.chmod(file.getAbsolutePath(), 0755);
            } catch (ErrnoException e) {
                Log.d(TAG, "Os.chmod(0755) skipped for " + file.getName() + " (setExecutable=" + execOk + ", setReadable=" + readableOk + ")");
            }
        }

        private static void applyExecutablePermissionsRecursively(File dir) {
            File[] children = dir.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    applyExecutablePermissionsRecursively(child);
                } else {
                    String name = child.getName();
                    File parent = child.getParentFile();
                    boolean inBin = parent != null && parent.getName().equals("bin");
                    if (inBin || name.equals("jspawnhelper") || name.endsWith(".so") || name.contains(".so.") || name.endsWith(".sh")) {
                        makeFileExecutable(child);
                    }
                }
            }
        }

        private static void moveContentsUpToParent(File sourceDir, File targetRootDir) throws IOException {
            File[] children = sourceDir.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                if (child.equals(targetRootDir)) {
                    continue;
                }
                File dest = new File(targetRootDir, child.getName());
                if (child.isDirectory()) {
                    copyDirectoryRecursively(child, dest);
                    deleteDirectoryRecursively(child);
                } else {
                    Files.move(child.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            sourceDir.delete();
        }

        private static File findFileByNameRecursively(File dir, String fileName) {
            File[] children = dir.listFiles();
            if (children == null) {
                return null;
            }
            for (File child : children) {
                if (child.isFile() && child.getName().equals(fileName)) {
                    return child;
                }
                if (child.isDirectory() && !child.getName().equals(".workshop_staging")) {
                    File found = findFileByNameRecursively(child, fileName);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        /**
         * Упаковывает содержимое папки инстанса в ZIP-архив так, чтобы `ProjectZomboid64`
         * находился строго в корне архива (требование стандартного импортёра Zomdroid,
         * см. [Steam game download done — now what?](https://www.reddit.com/r/zomdroid/comments/1u6gm6w/steam_game_download_done_now_what_a_quick_guide/)).
         */
        public static void packInstanceRootToZip(
                File instanceRootDir,
                File outputZipFile,
                AtomicBoolean cancelSignal
        ) throws IOException {
            File parent = outputZipFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputZipFile)))) {
                File[] children = instanceRootDir.listFiles();
                if (children == null) {
                    return;
                }
                byte[] buffer = new byte[64 * 1024];
                for (File child : children) {
                    if (child.getName().equals(".DepotDownloader") || child.getName().equals(".workshop_staging")) {
                        continue;
                    }
                    zipNodeRecursively(child, child.getName(), zos, buffer, cancelSignal);
                }
            }
        }

        private static void zipNodeRecursively(
                File node,
                String entryPath,
                ZipOutputStream zos,
                byte[] buffer,
                AtomicBoolean cancelSignal
        ) throws IOException {
            if (cancelSignal != null && cancelSignal.get()) {
                throw new CancellationException("Создание ZIP прервано пользователем");
            }
            String normalizedEntry = entryPath.replace(File.separatorChar, '/');
            if (node.isDirectory()) {
                String dirEntry = normalizedEntry.endsWith("/") ? normalizedEntry : (normalizedEntry + "/");
                zos.putNextEntry(new ZipEntry(dirEntry));
                zos.closeEntry();
                File[] children = node.listFiles();
                if (children != null) {
                    for (File child : children) {
                        zipNodeRecursively(child, dirEntry + child.getName(), zos, buffer, cancelSignal);
                    }
                }
            } else {
                ZipEntry entry = new ZipEntry(normalizedEntry);
                entry.setTime(node.lastModified());
                zos.putNextEntry(entry);
                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(node))) {
                    int read;
                    while ((read = bis.read(buffer)) != -1) {
                        if (cancelSignal != null && cancelSignal.get()) {
                            throw new CancellationException("Создание ZIP прервано пользователем");
                        }
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    // =============================================================================================
    // 8. ANDROID FOREGROUND SERVICE (SteamDownloadService) С WAKELOCK И УВЕДОМЛЕНИЯМИ ПРОГРЕССА
    // =============================================================================================

    /**
     * Android Foreground Service `SteamDownloadService` для фоновой загрузки Project Zomboid
     * (`AppID = 108600`, `DepotID = 108603`) и модов Steam Workshop в Zomdroid.
     *
     * Обеспечивает:
     * - Удержание `PowerManager.WakeLock` (`PARTIAL_WAKE_LOCK`) и `WifiManager.WifiLock`
     *   для предотвращения засыпания CPU и Wi-Fi при скачивании 6–11 ГБ файлов игры.
     * - Отображение системного уведомления с прогресс-баром, скоростью в МБ/с, текущим файлом
     *   и кнопкой отмены загрузки.
     * - Автоматический вызов `setExecutable(true, false)` и `Os.chmod(..., 0755)` для `ProjectZomboid64`
     *   сразу после окончания скачивания.
     */
    public static final class SteamDownloadService extends Service {
        public static final String ACTION_START_GAME_DOWNLOAD = "com.zomdroid.steam.action.START_GAME_DOWNLOAD";
        public static final String ACTION_START_MODS_DOWNLOAD = "com.zomdroid.steam.action.START_MODS_DOWNLOAD";
        public static final String ACTION_CANCEL_DOWNLOAD = "com.zomdroid.steam.action.CANCEL_DOWNLOAD";

        public static final String EXTRA_INSTANCE_DIR = "extra_instance_dir";
        public static final String EXTRA_ZOMBOID_USER_DIR = "extra_zomboid_user_dir";
        public static final String EXTRA_BRANCH = "extra_branch";
        public static final String EXTRA_BRANCH_PASSWORD = "extra_branch_password";
        public static final String EXTRA_MANIFEST_ID = "extra_manifest_id";
        public static final String EXTRA_WORKSHOP_IDS = "extra_workshop_ids";
        public static final String EXTRA_EXPORT_ZIP = "extra_export_zip";
        public static final String EXTRA_OUTPUT_ZIP_PATH = "extra_output_zip_path";
        public static final String EXTRA_MAX_THREADS = "extra_max_threads";
        public static final String EXTRA_USE_HIGH_LEVEL_DOWNLOADER = "extra_use_high_level_downloader";

        private static final String NOTIFICATION_CHANNEL_ID = "zomdroid_steam_download_channel";
        private static final int NOTIFICATION_ID = 108603;
        private static final long WAKELOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(4);
        private static final long NOTIFICATION_THROTTLE_MS = 500L;

        private final IBinder binder = new LocalBinder();
        private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Zomdroid-SteamDownloadWorker");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final List<DownloadProgressListener> uiListeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean activeDownloadRunning = new AtomicBoolean(false);
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        private NotificationManager notificationManager;
        private PowerManager.WakeLock cpuWakeLock;
        private WifiManager.WifiLock wifiLock;
        private SteamSessionManager sessionManager;
        private ZomboidContentDownloader contentDownloader;
        private volatile long lastNotificationUpdateMs = 0L;

        public final class LocalBinder extends Binder {
            public SteamDownloadService getService() {
                return SteamDownloadService.this;
            }
        }

        /**
         * Вспомогательный метод запуска загрузки игры Project Zomboid (Build 41 `public` или Build 42 `unstable`).
         */
        public static void startGameDownload(
                Context context,
                File instanceDir,
                String branch,
                long manifestId,
                long[] optionalWorkshopIds,
                boolean exportZip
        ) {
            Intent intent = new Intent(context, SteamDownloadService.class);
            intent.setAction(ACTION_START_GAME_DOWNLOAD);
            intent.putExtra(EXTRA_INSTANCE_DIR, instanceDir.getAbsolutePath());
            intent.putExtra(EXTRA_BRANCH, branch);
            intent.putExtra(EXTRA_MANIFEST_ID, manifestId);
            intent.putExtra(EXTRA_EXPORT_ZIP, exportZip);
            if (optionalWorkshopIds != null && optionalWorkshopIds.length > 0) {
                intent.putExtra(EXTRA_WORKSHOP_IDS, optionalWorkshopIds);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }

        /**
         * Вспомогательный метод запуска загрузки модов Steam Workshop по `PublishedFileId` (`pubfile`)
         * и раскладки их в `Zomboid/mods/`.
         */
        public static void startWorkshopModsDownload(
                Context context,
                File instanceDir,
                File zomboidUserDir,
                long[] publishedFileIds
        ) {
            Intent intent = new Intent(context, SteamDownloadService.class);
            intent.setAction(ACTION_START_MODS_DOWNLOAD);
            intent.putExtra(EXTRA_INSTANCE_DIR, instanceDir.getAbsolutePath());
            if (zomboidUserDir != null) {
                intent.putExtra(EXTRA_ZOMBOID_USER_DIR, zomboidUserDir.getAbsolutePath());
            }
            intent.putExtra(EXTRA_WORKSHOP_IDS, publishedFileIds);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }

        /**
         * Вспомогательный метод отмены текущей активной загрузки.
         */
        public static void cancelActiveDownload(Context context) {
            Intent intent = new Intent(context, SteamDownloadService.class);
            intent.setAction(ACTION_CANCEL_DOWNLOAD);
            context.startService(intent);
        }

        @Override
        public void onCreate() {
            super.onCreate();
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            createNotificationChannel();
            sessionManager = new SteamSessionManager(this);
            contentDownloader = new ZomboidContentDownloader(sessionManager);
        }

        @Override
        public IBinder onBind(Intent intent) {
            return binder;
        }

        public SteamSessionManager getSessionManager() {
            return sessionManager;
        }

        public boolean isDownloadRunning() {
            return activeDownloadRunning.get();
        }

        public void registerProgressListener(DownloadProgressListener listener) {
            if (listener != null && !uiListeners.contains(listener)) {
                uiListeners.add(listener);
            }
        }

        public void unregisterProgressListener(DownloadProgressListener listener) {
            uiListeners.remove(listener);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null || intent.getAction() == null) {
                return START_NOT_STICKY;
            }

            String action = intent.getAction();
            if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
                cancelRequested.set(true);
                updateForegroundNotification("Отмена загрузки...", "Остановка потоков CDN...", 0, true);
                return START_NOT_STICKY;
            }

            if (ACTION_START_GAME_DOWNLOAD.equals(action) || ACTION_START_MODS_DOWNLOAD.equals(action)) {
                if (!activeDownloadRunning.compareAndSet(false, true)) {
                    Log.w(TAG, "Download is already in progress, ignoring duplicate start command");
                    return START_STICKY;
                }

                cancelRequested.set(false);
                Notification initialNotification = buildProgressNotification(
                        "Подготовка загрузки Project Zomboid",
                        "Инициализация клиента Steam...",
                        0,
                        true
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                            NOTIFICATION_ID,
                            initialNotification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    );
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification);
                }

                DownloadRequest request = parseDownloadRequestFromIntent(intent);
                downloadExecutor.submit(() -> runDownloadTask(request));
                return START_STICKY;
            }

            return START_NOT_STICKY;
        }

        private DownloadRequest parseDownloadRequestFromIntent(Intent intent) {
            String action = intent.getAction();
            String instancePath = intent.getStringExtra(EXTRA_INSTANCE_DIR);
            File instanceDir = (instancePath != null && !instancePath.isEmpty())
                    ? new File(instancePath)
                    : new File(getExternalFilesDir(null), "instances/default");

            DownloadRequest.Builder builder = new DownloadRequest.Builder(instanceDir);

            String zomboidUserPath = intent.getStringExtra(EXTRA_ZOMBOID_USER_DIR);
            if (zomboidUserPath != null && !zomboidUserPath.isEmpty()) {
                builder.withZomboidUserDir(new File(zomboidUserPath));
            }

            if (ACTION_START_MODS_DOWNLOAD.equals(action)) {
                // Только загрузка модов Workshop без скачивания депо игры
                builder.withBranch("");
            } else {
                String branch = intent.getStringExtra(EXTRA_BRANCH);
                builder.withBranch(branch != null ? branch : ZomboidSteamConfig.BRANCH_PUBLIC);
                builder.withBranchPassword(intent.getStringExtra(EXTRA_BRANCH_PASSWORD));
                long manifestId = intent.getLongExtra(EXTRA_MANIFEST_ID, ZomboidSteamConfig.MANIFEST_LATEST);
                builder.withManifestId(manifestId);
            }

            long[] workshopIds = intent.getLongArrayExtra(EXTRA_WORKSHOP_IDS);
            if (workshopIds != null) {
                for (long id : workshopIds) {
                    builder.addWorkshopMod(id);
                }
            }

            boolean exportZip = intent.getBooleanExtra(EXTRA_EXPORT_ZIP, false);
            String outZipPath = intent.getStringExtra(EXTRA_OUTPUT_ZIP_PATH);
            File outZipFile = (outZipPath != null && !outZipPath.isEmpty()) ? new File(outZipPath) : null;
            builder.withExportInstanceZip(exportZip, outZipFile);

            int threads = intent.getIntExtra(EXTRA_MAX_THREADS, 8);
            builder.withMaxParallelChunks(threads);

            boolean highLevel = intent.getBooleanExtra(EXTRA_USE_HIGH_LEVEL_DOWNLOADER, false);
            builder.withHighLevelDepotDownloader(highLevel);

            return builder.build();
        }

        private void runDownloadTask(DownloadRequest request) {
            acquireLocks();
            try {
                contentDownloader.executeDownload(request, new DownloadProgressListener() {
                    @Override
                    public void onStageChanged(String stageDescription) {
                        updateForegroundNotification("Загрузка Project Zomboid", stageDescription, 0, true);
                        mainHandler.post(() -> {
                            for (DownloadProgressListener l : uiListeners) {
                                l.onStageChanged(stageDescription);
                            }
                        });
                    }

                    @Override
                    public void onProgressUpdated(
                            long bytesDownloaded,
                            long totalBytes,
                            long speedBytesPerSec,
                            String currentFileName,
                            int overallPercent
                    ) {
                        long now = System.currentTimeMillis();
                        if (now - lastNotificationUpdateMs >= NOTIFICATION_THROTTLE_MS || overallPercent == 100) {
                            lastNotificationUpdateMs = now;
                            String statsText = formatProgressStats(bytesDownloaded, totalBytes, speedBytesPerSec, currentFileName);
                            updateForegroundNotification(
                                    "Скачивание Project Zomboid (" + overallPercent + "%)",
                                    statsText,
                                    overallPercent,
                                    false
                            );
                        }
                        mainHandler.post(() -> {
                            for (DownloadProgressListener l : uiListeners) {
                                l.onProgressUpdated(bytesDownloaded, totalBytes, speedBytesPerSec, currentFileName, overallPercent);
                            }
                        });
                    }

                    @Override
                    public void onWorkshopModInstalled(long publishedFileId, String modTitle, List<String> installedModFolders) {
                        String msg = "Мод установлен в Zomboid/mods: " + modTitle;
                        updateForegroundNotification("Steam Workshop", msg, 100, false);
                        mainHandler.post(() -> {
                            for (DownloadProgressListener l : uiListeners) {
                                l.onWorkshopModInstalled(publishedFileId, modTitle, installedModFolders);
                            }
                        });
                    }

                    @Override
                    public void onDownloadCompleted(File instanceRootDir, File executableFile, File optionalZipFile) {
                        // Финальная проверка и установка флага исполняемости для ProjectZomboid64
                        if (executableFile != null && executableFile.exists()) {
                            boolean execSet = executableFile.setExecutable(true, false);
                            ZomboidPostInstallProcessor.makeFileExecutable(executableFile);
                            Log.i(TAG, "ProjectZomboid64 executable flag verified: " + execSet
                                    + " (" + executableFile.getAbsolutePath() + ")");
                        }

                        showCompletionNotification(
                                "Загрузка завершена",
                                "Project Zomboid и моды готовы к запуску в Zomdroid."
                        );

                        mainHandler.post(() -> {
                            for (DownloadProgressListener l : uiListeners) {
                                l.onDownloadCompleted(instanceRootDir, executableFile, optionalZipFile);
                            }
                        });
                    }

                    @Override
                    public void onDownloadFailed(String errorMessage, Throwable error) {
                        showCompletionNotification("Ошибка загрузки Steam", errorMessage);
                        mainHandler.post(() -> {
                            for (DownloadProgressListener l : uiListeners) {
                                l.onDownloadFailed(errorMessage, error);
                            }
                        });
                    }
                }, cancelRequested);
            } catch (CancellationException ce) {
                Log.i(TAG, "Steam download cancelled by user");
                showCompletionNotification("Загрузка отменена", "Скачивание файлов Steam было остановлено.");
                mainHandler.post(() -> {
                    for (DownloadProgressListener l : uiListeners) {
                        l.onDownloadFailed("Загрузка отменена пользователем", ce);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Steam download task failed", e);
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                showCompletionNotification("Ошибка загрузки Steam", errMsg);
                mainHandler.post(() -> {
                    for (DownloadProgressListener l : uiListeners) {
                        l.onDownloadFailed(errMsg, e);
                    }
                });
            } finally {
                activeDownloadRunning.set(false);
                releaseLocks();
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }
        }

        private void acquireLocks() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zomdroid:SteamDownloadWakeLock");
                    cpuWakeLock.setReferenceCounted(false);
                    cpuWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not acquire CPU WakeLock", e);
            }

            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int lockMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(lockMode, "Zomdroid:SteamDownloadWifiLock");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not acquire WifiLock", e);
            }
        }

        private void releaseLocks() {
            try {
                if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                    cpuWakeLock.release();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error releasing CPU WakeLock", e);
            }
            try {
                if (wifiLock != null && wifiLock.isHeld()) {
                    wifiLock.release();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error releasing WifiLock", e);
            }
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Загрузка Project Zomboid и модов Steam",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Уведомления о прогрессе прямой загрузки файлов Project Zomboid и Workshop из Steam");
                channel.setShowBadge(false);
                notificationManager.createNotificationChannel(channel);
            }
        }

        private Notification buildProgressNotification(
                String title,
                String contentText,
                int progressPercent,
                boolean indeterminate
        ) {
            Intent cancelIntent = new Intent(this, SteamDownloadService.class);
            cancelIntent.setAction(ACTION_CANCEL_DOWNLOAD);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent cancelPendingIntent = PendingIntent.getService(this, 0, cancelIntent, pendingFlags);

            return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(contentText)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, Math.min(100, Math.max(0, progressPercent)), indeterminate)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancelPendingIntent)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .build();
        }

        private void updateForegroundNotification(
                String title,
                String contentText,
                int progressPercent,
                boolean indeterminate
        ) {
            if (notificationManager != null) {
                Notification notification = buildProgressNotification(title, contentText, progressPercent, indeterminate);
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        }

        private void showCompletionNotification(String title, String message) {
            if (notificationManager != null) {
                Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .build();
                notificationManager.notify(NOTIFICATION_ID + 1, notification);
            }
        }

        private static String formatProgressStats(
                long downloadedBytes,
                long totalBytes,
                long speedBytesPerSec,
                String currentFileName
        ) {
            double dlMb = downloadedBytes / (1024.0 * 1024.0);
            double totalMb = totalBytes / (1024.0 * 1024.0);
            double speedMb = speedBytesPerSec / (1024.0 * 1024.0);
            String shortFile = currentFileName != null ? new File(currentFileName).getName() : "";
            if (totalBytes > 0) {
                return String.format(
                        Locale.US,
                        "%.1f МБ / %.1f МБ (%.2f МБ/с) • %s",
                        dlMb,
                        totalMb,
                        speedMb,
                        shortFile
                );
            }
            return String.format(Locale.US, "%.1f МБ (%.2f МБ/с) • %s", dlMb, speedMb, shortFile);
        }

        @Override
        public void onDestroy() {
            cancelRequested.set(true);
            releaseLocks();
            downloadExecutor.shutdownNow();
            if (sessionManager != null) {
                sessionManager.close();
            }
            super.onDestroy();
        }
    }

    // =============================================================================================
    // 9. ФАЙЛОВЫЕ УТИЛИТЫ
    // =============================================================================================

    static void copyDirectoryRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Не удалось создать папку: " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirectoryRecursively(child, new File(target, child.getName()));
                }
            }
        } else {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Не удалось создать родительскую папку: " + parent.getAbsolutePath());
            }
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (source.canExecute()) {
                ZomboidPostInstallProcessor.makeFileExecutable(target);
            }
        }
    }

    static void deleteDirectoryRecursively(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) {
            return;
        }
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryRecursively(child);
                }
            }
        }
        fileOrDir.delete();
    }
}
