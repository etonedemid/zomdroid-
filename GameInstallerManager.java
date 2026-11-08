package com.zomdroid.installer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Автономный менеджер установки игровых инстансов и модов для Android-лаунчера Zomdroid.
 *
 * Источники и спецификации:
 * - Оригинальный репозиторий Zomdroid: https://github.com/liamelui/zomdroid
 * - Форк Zomdroid-: https://github.com/etonedemid/zomdroid-
 * - Руководство по подготовке ZIP-архива (Linux-ветка Project Zomboid):
 *   https://www.reddit.com/r/zomdroid/comments/1o5qo06/guide_creating_a_zip_for_the_zomdroid_launcher/
 * - Zomdroid Launcher FAQ:
 *   https://www.reddit.com/r/zomdroid/comments/1ohacmn/zomdroid_launcher_faq/
 * - Структура Linux-депо Project Zomboid (AppID 108600, Depot 108603):
 *   https://steamdb.info/depot/108603/manifests/
 * - Спецификация распаковки инсталляторов GOG MojoSetup (.sh):
 *   https://github.com/Yepoleb/gogextract и https://www.talospace.com/2021/03/tonights-game-on-openpower-original.html
 * - Steamworks Web API (ISteamRemoteStorage/GetPublishedFileDetails/v1):
 *   https://partner.steamgames.com/doc/webapi/isteamremotestorage
 */
public class GameInstallerManager {

    /** Steam AppID игры Project Zomboid (https://steamdb.info/depot/108603/manifests/) */
    public static final int PZ_STEAM_APP_ID = 108600;
    /** Steam Depot ID Linux-версии Project Zomboid (https://steamdb.info/depot/108603/manifests/) */
    public static final int PZ_LINUX_DEPOT_ID = 108603;

    /** Сигнатура Local File Header стандарта PKZIP (PK\x03\x04 / 0x504B0304) */
    private static final byte[] ZIP_LOCAL_HEADER_MAGIC = new byte[]{0x50, 0x4B, 0x03, 0x04};

    /** Внутренний путь к файлам игры внутри архива MojoSetup GOG (.sh) */
    public static final String GOG_MOJOSETUP_GAME_PREFIX = "data/noarch/game/";

    /** Ключевой исполняемый бинарник Linux-версии Project Zomboid */
    public static final String PZ_MAIN_BINARY = "ProjectZomboid64";

    /** Ключевая директория ресурсов игры */
    public static final String PZ_MEDIA_DIR = "media/";

    /** Список известных исполняемых файлов Project Zomboid (Linux) */
    private static final Set<String> KNOWN_EXECUTABLES = new HashSet<>(Arrays.asList(
            "ProjectZomboid64",
            "ProjectZomboid32",
            "projectzomboid.sh",
            "start.sh",
            "jre64/bin/java",
            "jre64/bin/jfr",
            "jre64/bin/jrunscript",
            "jre64/bin/jwebserver",
            "jre64/bin/keytool",
            "jre64/bin/rmiregistry",
            "jre64/lib/jexec",
            "jre64/lib/jspawnhelper"
    ));

    private static final int IO_BUFFER_SIZE = 64 * 1024; // 64 КБ буфер для быстрой потоковой распаковки

    private final Context appContext;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public GameInstallerManager(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.executorService = Executors.newCachedThreadPool();
        this.mainHandler = Looper.getMainLooper() != null ? new Handler(Looper.getMainLooper()) : null;
    }

    /**
     * Интерфейс обратного вызова для отслеживания прогресса установки или загрузки.
     */
    public interface InstallProgressListener {
        void onPhaseChanged(String phaseDescription);
        void onProgress(long bytesProcessed, long totalBytes, String currentFile);
        void onCompleted(InstallResult result);
        void onError(Exception error);
    }

    /**
     * Результат успешной распаковки/установки инстанса или мода.
     */
    public static class InstallResult {
        public final File targetDirectory;
        public final int extractedFilesCount;
        public final long totalExtractedBytes;
        public final String strippedRootPrefix;
        public final List<String> executableFilesConfigured;

        public InstallResult(File targetDirectory,
                             int extractedFilesCount,
                             long totalExtractedBytes,
                             String strippedRootPrefix,
                             List<String> executableFilesConfigured) {
            this.targetDirectory = targetDirectory;
            this.extractedFilesCount = extractedFilesCount;
            this.totalExtractedBytes = totalExtractedBytes;
            this.strippedRootPrefix = strippedRootPrefix;
            this.executableFilesConfigured = Collections.unmodifiableList(executableFilesConfigured);
        }
    }

    /**
     * Метаданные мода из Steam Workshop, полученные через ISteamRemoteStorage.
     */
    public static class WorkshopModInfo {
        public final String publishedFileId;
        public final String title;
        public final long fileSize;
        public final String fileUrl;
        public final String previewUrl;
        public final int ConsumerAppId;

        public WorkshopModInfo(String publishedFileId, String title, long fileSize,
                               String fileUrl, String previewUrl, int consumerAppId) {
            this.publishedFileId = publishedFileId;
            this.title = title;
            this.fileSize = fileSize;
            this.fileUrl = fileUrl;
            this.previewUrl = previewUrl;
            this.ConsumerAppId = consumerAppId;
        }
    }

    /**
     * Специализированное исключение при некорректной структуре архива игры.
     */
    public static class InvalidGameArchiveException extends IOException {
        public InvalidGameArchiveException(String message) {
            super(message);
        }
    }

    // ============================================================================================
    // 1. РАСПАКОВКА ОФФЛАЙН-ИНСТАЛЛЯТОРОВ GOG (.sh НА БАЗЕ MOJOSETUP)
    // ============================================================================================

    /**
     * Асинхронно распаковывает Linux-инсталлятор GOG (project_zomboid_*.sh) из Android Uri в папку инстанса.
     */
    public Future<?> installFromGogShAsync(final Uri shFileUri,
                                           final File instanceDir,
                                           final AtomicBoolean cancelToken,
                                           final InstallProgressListener listener) {
        return executorService.submit(() -> {
            try {
                if (appContext == null) {
                    throw new IllegalStateException("Context не инициализирован для работы с ContentResolver");
                }
                ContentResolver resolver = appContext.getContentResolver();
                long totalSize = queryUriSize(resolver, shFileUri);
                try (InputStream rawStream = resolver.openInputStream(shFileUri)) {
                    if (rawStream == null) {
                        throw new IOException("Не удалось открыть поток чтения для файла: " + shFileUri);
                    }
                    InstallResult result = installFromGogShStream(rawStream, totalSize, instanceDir, cancelToken, listener);
                    notifyCompleted(listener, result);
                }
            } catch (Exception e) {
                notifyError(listener, e);
            }
        });
    }

    /**
     * Синхронно распаковывает Linux-инсталлятор GOG (project_zomboid_*.sh) из локального файла.
     */
    public InstallResult installFromGogShFile(File shFile,
                                              File instanceDir,
                                              AtomicBoolean cancelToken,
                                              InstallProgressListener listener) throws IOException {
        if (shFile == null || !shFile.exists()) {
            throw new IOException("Файл инсталлятора GOG (.sh) не найден: " + shFile);
        }
        try (InputStream rawStream = new FileInputStream(shFile)) {
            return installFromGogShStream(rawStream, shFile.length(), instanceDir, cancelToken, listener);
        }
    }

    /**
     * Находит сигнатуру начала ZIP-архива в потоке инсталлятора GOG (.sh, построенного на базе
     * Makeself + MojoSetup) и извлекает файлы игры из data/noarch/game/ напрямую в директорию инстанса.
     *
     * Архитектура инсталлятора GOG (.sh):
     * 1. В начале файла идет shell-скрипт Makeself (обычно 10–25 КБ).
     * 2. Далее идет tar.gz архив самого движка MojoSetup (размер указан в переменной filesizes="...").
     * 3. Сразу после него приклеен ZIP-архив (начинающийся с сигнатуры PK\x03\x04), содержащий
     *    скрипты установки и директорию data/noarch/game/ со всеми файлами Project Zomboid.
     */
    public InstallResult installFromGogShStream(InputStream rawInputStream,
                                                long totalFileBytes,
                                                File instanceDir,
                                                AtomicBoolean cancelToken,
                                                InstallProgressListener listener) throws IOException {
        if (!instanceDir.exists() && !instanceDir.mkdirs()) {
            throw new IOException("Не удалось создать директорию инстанса: " + instanceDir.getAbsolutePath());
        }

        notifyPhase(listener, "Поиск встроенного ZIP-архива в инсталляторе GOG (.sh)...");

        CountingInputStream countingStream = new CountingInputStream(new BufferedInputStream(rawInputStream, IO_BUFFER_SIZE));
        InputStream positionedZipStream = seekToMojoSetupZipPayload(countingStream);

        notifyPhase(listener, "Извлечение файлов игры из data/noarch/game/...");

        List<String> configuredExecutables = new ArrayList<>();
        int extractedCount = 0;
        long extractedBytes = 0L;
        byte[] buffer = new byte[IO_BUFFER_SIZE];

        String canonicalDestRoot = instanceDir.getCanonicalPath() + File.separator;

        try (ZipInputStream zis = new ZipInputStream(positionedZipStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                checkCancelled(cancelToken);

                String rawName = normalizeZipPath(entry.getName());
                if (rawName.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                // Извлекаем только содержимое data/noarch/game/
                if (!rawName.startsWith(GOG_MOJOSETUP_GAME_PREFIX)) {
                    zis.closeEntry();
                    continue;
                }

                String relativePath = rawName.substring(GOG_MOJOSETUP_GAME_PREFIX.length());
                relativePath = stripLeadingSlashes(relativePath);
                if (relativePath.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(instanceDir, relativePath);
                String canonicalOutPath = outFile.getCanonicalPath();
                if (!canonicalOutPath.startsWith(canonicalDestRoot)) {
                    throw new SecurityException("Обнаружена попытка выхода за пределы папки (Zip Slip): " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Не удалось создать папку: " + outFile.getAbsolutePath());
                    }
                    zis.closeEntry();
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Не удалось создать родительскую папку: " + parent.getAbsolutePath());
                }

                byte[] firstFourBytes = new byte[4];
                int headerBytesRead = 0;

                try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile), IO_BUFFER_SIZE)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        checkCancelled(cancelToken);
                        if (headerBytesRead < 4) {
                            int toCopy = Math.min(4 - headerBytesRead, read);
                            System.arraycopy(buffer, 0, firstFourBytes, headerBytesRead, toCopy);
                            headerBytesRead += toCopy;
                        }
                        fos.write(buffer, 0, read);
                        extractedBytes += read;
                    }
                }

                // Выставляем права на исполнение для бинарников, скриптов и нативных библиотек
                if (shouldMarkExecutable(relativePath, firstFourBytes, headerBytesRead)) {
                    applyExecutablePermissions(outFile);
                    configuredExecutables.add(relativePath);
                }

                extractedCount++;
                notifyProgress(listener, countingStream.getBytesRead(), totalFileBytes, relativePath);
                zis.closeEntry();
            }
        }

        if (extractedCount == 0) {
            throw new InvalidGameArchiveException(
                    "В инсталляторе GOG (.sh) не найдены файлы по пути " + GOG_MOJOSETUP_GAME_PREFIX +
                    ". Убедитесь, что выбран Linux-инсталлятор Project Zomboid от GOG.");
        }

        // Если внутри data/noarch/game/ файлы оказались дополнительно вложены в подпапку, сдвигаем корень
        String postShiftPrefix = flattenNestedGameRootIfNeeded(instanceDir, configuredExecutables);
        validateExtractedInstance(instanceDir);

        String fullStrippedPrefix = GOG_MOJOSETUP_GAME_PREFIX + (postShiftPrefix.isEmpty() ? "" : postShiftPrefix);
        return new InstallResult(instanceDir, extractedCount, extractedBytes, fullStrippedPrefix, configuredExecutables);
    }

    /**
     * Ищет начало ZIP-архива в потоке GOG .sh (Makeself + MojoSetup).
     * Поддерживает как точный пропуск tar.gz блока по заголовку Makeself (filesizes= / offset=),
     * так и побайтовое потоковое сканирование сигнатуры Local File Header (0x50 0x4B 0x03 0x04)
     * с проверкой валидности заголовка записи ZIP, чтобы исключить ложные совпадения внутри сжатого tar.gz.
     */
    public InputStream seekToMojoSetupZipPayload(InputStream source) throws IOException {
        // Оборачиваем поток, чтобы иметь возможность вернуть прочитанные байты сигнатуры PK\x03\x04
        // Используем окно предпросмотра 64 байта для валидации Local File Header
        final int lookaheadSize = 64;
        byte[] ring = new byte[lookaheadSize];
        int ringFill = 0;

        // Читаем первые байты для проверки, не является ли поток сразу ZIP-архивом,
        // а также для анализа текстового заголовка Makeself (первые 32 КБ)
        ByteArrayOutputStream scriptHeaderBuffer = new ByteArrayOutputStream(16384);
        boolean scriptHeaderParsed = false;
        long bytesReadTotal = 0;

        while (true) {
            while (ringFill < lookaheadSize) {
                int b = source.read();
                if (b == -1) {
                    break;
                }
                ring[ringFill++] = (byte) b;
                bytesReadTotal++;

                if (!scriptHeaderParsed && scriptHeaderBuffer.size() < 32768) {
                    scriptHeaderBuffer.write(b);
                    // Проверяем, не закончился ли shell-скрипт маркером Makeself (eval $finish; exit)
                    if (b == '\n' && scriptHeaderBuffer.size() > 256) {
                        String headerText = new String(scriptHeaderBuffer.toByteArray(), StandardCharsets.ISO_8859_1);
                        long skipBytes = tryComputeMakeselfMojoSetupSkip(headerText, bytesReadTotal);
                        if (skipBytes > 0) {
                            scriptHeaderParsed = true;
                            // Очищаем текущий буфер ring и пропускаем блок mojosetup.tar.gz напрямую
                            long remainingToSkip = skipBytes;
                            ringFill = 0;
                            while (remainingToSkip > 0) {
                                long skipped = source.skip(remainingToSkip);
                                if (skipped <= 0) {
                                    if (source.read() == -1) {
                                        break;
                                    }
                                    skipped = 1;
                                }
                                remainingToSkip -= skipped;
                                bytesReadTotal += skipped;
                            }
                            break;
                        }
                    }
                } else {
                    scriptHeaderParsed = true;
                }
            }

            if (ringFill < 4) {
                throw new InvalidGameArchiveException(
                        "Сигнатура ZIP-архива (0x504B0304) не найдена в потоке GOG инсталлятора.");
            }

            // Проверяем совпадение с PK\x03\x04 (0x50, 0x4B, 0x03, 0x04)
            if (ring[0] == ZIP_LOCAL_HEADER_MAGIC[0]
                    && ring[1] == ZIP_LOCAL_HEADER_MAGIC[1]
                    && ring[2] == ZIP_LOCAL_HEADER_MAGIC[2]
                    && ring[3] == ZIP_LOCAL_HEADER_MAGIC[3]) {

                if (isValidMojoSetupZipLocalHeader(ring, ringFill)) {
                    // Возвращаем поток, начинающийся ровно с найденных байтов PK\x03\x04
                    final byte[] prefixBytes = Arrays.copyOf(ring, ringFill);
                    return new PrependInputStream(prefixBytes, source);
                }
            }

            // Сдвигаем окно на 1 байт
            System.arraycopy(ring, 1, ring, 0, ringFill - 1);
            ringFill--;
        }
    }

    /**
     * Проверяет, содержит ли текстовый заголовок Makeself точные смещения (offset=head -n N и filesizes="...")
     * и вычисляет количество байт, которые можно безопасно перепрыгнуть до начала ZIP-архива.
     */
    private long tryComputeMakeselfMojoSetupSkip(String headerText, long currentStreamPos) {
        // Ищем конец скрипта makeself, который обычно заканчивается строкой `exit $res` или маркером EOF
        int evalExitIdx = headerText.indexOf("eval $finish; exit");
        if (evalExitIdx < 0) {
            return -1;
        }
        int newlineAfterExit = headerText.indexOf('\n', evalExitIdx);
        if (newlineAfterExit < 0) {
            return -1;
        }
        Matcher filesizeMatcher = Pattern.compile("filesizes=\"(\\d+)\"").matcher(headerText);
        if (!filesizeMatcher.find()) {
            return -1;
        }
        try {
            long mojoTarGzSize = Long.parseLong(filesizeMatcher.group(1));
            long zipStartOffset = (newlineAfterExit + 1) + mojoTarGzSize;
            // Оставляем запас в 64 байта перед началом ZIP, чтобы потоковый сканер точно поймал PK\x03\x04
            long safeTargetOffset = Math.max(currentStreamPos, zipStartOffset - 64);
            return safeTargetOffset - currentStreamPos;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Валидирует структуру ZIP Local File Header (30 байт + имя файла), чтобы исключить
     * случайную последовательность байт 50 4B 03 04 внутри сжатых данных tar.gz.
     */
    private boolean isValidMojoSetupZipLocalHeader(byte[] header, int length) {
        if (length < 31) {
            return false;
        }
        // Байты 4..5: Version needed to extract (обычно 10, 20 или 45; младший байт <= 63, старший 0..3)
        int versionNeeded = (header[4] & 0xFF) | ((header[5] & 0xFF) << 8);
        int versionLow = header[4] & 0xFF;
        if (versionLow < 10 || versionLow > 63 || (header[5] & 0xFF) > 3) {
            return false;
        }
        // Байты 8..9: Compression method (0 = STORE, 8 = DEFLATE)
        int compressionMethod = (header[8] & 0xFF) | ((header[9] & 0xFF) << 8);
        if (compressionMethod != 0 && compressionMethod != 8) {
            return false;
        }
        // Байты 26..27: File name length (должна быть > 0 и <= 512)
        int fileNameLen = (header[26] & 0xFF) | ((header[27] & 0xFF) << 8);
        if (fileNameLen <= 0 || fileNameLen > 512) {
            return false;
        }
        // Проверяем, что первые символы имени файла (начиная с 30-го байта) — печатные ASCII-символы пути
        int checkChars = Math.min(fileNameLen, length - 30);
        for (int i = 0; i < checkChars; i++) {
            int ch = header[30 + i] & 0xFF;
            if (ch < 0x20 || ch > 0x7E) {
                return false;
            }
        }
        return versionNeeded > 0;
    }

    // ============================================================================================
    // 2. УМНЫЙ ИМПОРТ .ZIP АРХИВОВ С АВТОМАТИЧЕСКИМ СДВИГОМ КОРНЕВОГО ПУТИ И ПРАВАМИ ИСПОЛНЕНИЯ
    // ============================================================================================

    /**
     * Асинхронно импортирует обычный .zip архив игры из Android Uri с автоматическим исправлением
     * лишних вложенных папок и установкой прав setExecutable(true) на бинарники.
     */
    public Future<?> installFromZipUriAsync(final Uri zipUri,
                                            final File instanceDir,
                                            final AtomicBoolean cancelToken,
                                            final InstallProgressListener listener) {
        return executorService.submit(() -> {
            try {
                if (appContext == null) {
                    throw new IllegalStateException("Context не инициализирован для работы с ContentResolver");
                }
                ContentResolver resolver = appContext.getContentResolver();
                long totalSize = queryUriSize(resolver, zipUri);

                // Проход 1: Быстрое сканирование структуры ZIP для определения вложенного корня
                notifyPhase(listener, "Анализ структуры ZIP-архива и поиск ProjectZomboid64 / media/...");
                String detectedPrefix;
                try (InputStream scanStream = resolver.openInputStream(zipUri)) {
                    if (scanStream == null) {
                        throw new IOException("Не удалось открыть ZIP-архив для анализа: " + zipUri);
                    }
                    detectedPrefix = detectGameRootPrefixFromStream(scanStream, cancelToken);
                }

                // Проход 2: Потоковая распаковка со сдвигом корневого пути
                try (InputStream extractStream = resolver.openInputStream(zipUri)) {
                    if (extractStream == null) {
                        throw new IOException("Не удалось открыть ZIP-архив для распаковки: " + zipUri);
                    }
                    InstallResult result = extractZipStreamWithPrefixShift(
                            extractStream, totalSize, instanceDir, detectedPrefix, cancelToken, listener);
                    notifyCompleted(listener, result);
                }
            } catch (Exception e) {
                notifyError(listener, e);
            }
        });
    }

    /**
     * Синхронно импортирует .zip архив из локального файла с автоматическим определением
     * лишней вложенной папки (где лежат ProjectZomboid64 и папка media/), сдвигом корневого пути
     * и выставлением прав setExecutable(true).
     */
    public InstallResult installFromZipFile(File zipFile,
                                            File instanceDir,
                                            AtomicBoolean cancelToken,
                                            InstallProgressListener listener) throws IOException {
        if (zipFile == null || !zipFile.exists()) {
            throw new IOException("ZIP-архив не найден: " + zipFile);
        }

        notifyPhase(listener, "Анализ структуры ZIP-архива...");
        String detectedPrefix = detectGameRootPrefixFromZipFile(zipFile, cancelToken);

        try (InputStream fis = new FileInputStream(zipFile)) {
            return extractZipStreamWithPrefixShift(
                    fis, zipFile.length(), instanceDir, detectedPrefix, cancelToken, listener);
        }
    }

    /**
     * Однопроходный умный импорт из произвольного InputStream (если повторное открытие потока недоступно).
     * Распаковывает архив, затем автоматически обнаруживает вложенную папку с ProjectZomboid64 и media/,
     * сдвигает корневой путь в корень instanceDir и выставляет setExecutable(true).
     */
    public InstallResult installFromZipStream(InputStream zipInputStream,
                                              long totalSizeBytes,
                                              File instanceDir,
                                              AtomicBoolean cancelToken,
                                              InstallProgressListener listener) throws IOException {
        InstallResult initialResult = extractZipStreamWithPrefixShift(
                zipInputStream, totalSizeBytes, instanceDir, null, cancelToken, listener);

        List<String> updatedExecutables = new ArrayList<>(initialResult.executableFilesConfigured);
        String shiftedPrefix = flattenNestedGameRootIfNeeded(instanceDir, updatedExecutables);
        validateExtractedInstance(instanceDir);

        String finalPrefix = shiftedPrefix.isEmpty() ? initialResult.strippedRootPrefix : shiftedPrefix;
        return new InstallResult(
                instanceDir,
                initialResult.extractedFilesCount,
                initialResult.totalExtractedBytes,
                finalPrefix,
                updatedExecutables
        );
    }

    /**
     * Анализирует оглавление локального ZipFile (без распаковки данных) и определяет префикс лишней
     * вложенной папки, внутри которой находятся файл ProjectZomboid64 и/или директория media/.
     */
    public String detectGameRootPrefixFromZipFile(File zipFile, AtomicBoolean cancelToken) throws IOException {
        List<String> entryNames = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(cancelToken);
                ZipEntry entry = entries.nextElement();
                entryNames.add(normalizeZipPath(entry.getName()));
            }
        }
        return resolveGameRootPrefixFromEntries(entryNames);
    }

    /**
     * Анализирует поток ZipInputStream и определяет префикс лишней вложенной папки.
     */
    public String detectGameRootPrefixFromStream(InputStream zipStream, AtomicBoolean cancelToken) throws IOException {
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zipStream, IO_BUFFER_SIZE), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                checkCancelled(cancelToken);
                entryNames.add(normalizeZipPath(entry.getName()));
                zis.closeEntry();
            }
        }
        return resolveGameRootPrefixFromEntries(entryNames);
    }

    /**
     * Вычисляет корневой префикс игры по списку путей в архиве:
     * 1. Ищет точное расположение бинарника ProjectZomboid64 (или ProjectZomboid64.json).
     * 2. Проверяет наличие папки media/ на том же уровне вложенности.
     * 3. Распознает частую ошибку, когда пользователь упаковал Windows-версию игры (ProjectZomboid64.exe)
     *    вместо Linux-версии (Depot 108603).
     */
    public String resolveGameRootPrefixFromEntries(List<String> normalizedPaths) throws InvalidGameArchiveException {
        String binaryPrefix = null;
        String mediaPrefix = null;
        boolean hasWindowsExe = false;

        for (String path : normalizedPaths) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            if (path.equals(PZ_MAIN_BINARY) || path.endsWith("/" + PZ_MAIN_BINARY)) {
                int slashIdx = path.lastIndexOf('/');
                String candidate = (slashIdx == -1) ? "" : path.substring(0, slashIdx + 1);
                if (binaryPrefix == null || candidate.length() < binaryPrefix.length()) {
                    binaryPrefix = candidate;
                }
            }
            if (path.equals("ProjectZomboid64.exe") || path.endsWith("/ProjectZomboid64.exe")) {
                hasWindowsExe = true;
            }
            if (path.startsWith(PZ_MEDIA_DIR) || path.contains("/" + PZ_MEDIA_DIR)) {
                int mediaIdx = path.startsWith(PZ_MEDIA_DIR) ? 0 : path.indexOf("/" + PZ_MEDIA_DIR) + 1;
                String candidate = path.substring(0, mediaIdx);
                if (mediaPrefix == null || candidate.length() < mediaPrefix.length()) {
                    mediaPrefix = candidate;
                }
            }
        }

        if (binaryPrefix == null && hasWindowsExe) {
            throw new InvalidGameArchiveException(
                    "Обнаружен архив Windows-версии игры (ProjectZomboid64.exe). " +
                    "Для запуска в Zomdroid требуется Linux-версия Project Zomboid (Steam Depot 108603 или Linux-инсталлятор GOG).");
        }

        if (binaryPrefix != null) {
            return binaryPrefix;
        }
        if (mediaPrefix != null) {
            return mediaPrefix;
        }
        return "";
    }

    /**
     * Распаковывает поток ZIP-архива со сдвигом префикса (или динамическим определением префикса на лету)
     * и автоматически выставляет права setExecutable(true) на исполняемые файлы.
     */
    private InstallResult extractZipStreamWithPrefixShift(InputStream rawZipStream,
                                                          long totalZipBytes,
                                                          File instanceDir,
                                                          String precomputedPrefix,
                                                          AtomicBoolean cancelToken,
                                                          InstallProgressListener listener) throws IOException {
        if (!instanceDir.exists() && !instanceDir.mkdirs()) {
            throw new IOException("Не удалось создать директорию инстанса: " + instanceDir.getAbsolutePath());
        }

        String activePrefix = precomputedPrefix != null ? precomputedPrefix : "";
        if (!activePrefix.isEmpty()) {
            notifyPhase(listener, "Обнаружена лишняя вложенная папка «" + activePrefix + "». Автоматический сдвиг корня...");
        } else {
            notifyPhase(listener, "Распаковка архива игры в директорию инстанса...");
        }

        CountingInputStream countingStream = new CountingInputStream(new BufferedInputStream(rawZipStream, IO_BUFFER_SIZE));
        List<String> configuredExecutables = new ArrayList<>();
        int extractedFilesCount = 0;
        long totalExtractedBytes = 0L;
        byte[] buffer = new byte[IO_BUFFER_SIZE];

        String canonicalDestRoot = instanceDir.getCanonicalPath() + File.separator;

        try (ZipInputStream zis = new ZipInputStream(countingStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                checkCancelled(cancelToken);

                String normalizedPath = normalizeZipPath(entry.getName());
                if (normalizedPath.isEmpty() || normalizedPath.startsWith("__MACOSX/")) {
                    zis.closeEntry();
                    continue;
                }

                String relativePath = normalizedPath;
                if (!activePrefix.isEmpty()) {
                    if (!normalizedPath.startsWith(activePrefix)) {
                        // Пропускаем посторонние файлы выше корневой папки игры
                        zis.closeEntry();
                        continue;
                    }
                    relativePath = normalizedPath.substring(activePrefix.length());
                }

                relativePath = stripLeadingSlashes(relativePath);
                if (relativePath.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(instanceDir, relativePath);
                String canonicalOutPath = outFile.getCanonicalPath();
                if (!canonicalOutPath.startsWith(canonicalDestRoot)) {
                    throw new SecurityException("Заблокирована попытка Zip Slip атаки: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Не удалось создать директорию: " + outFile.getAbsolutePath());
                    }
                    zis.closeEntry();
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Не удалось создать родительскую директорию: " + parent.getAbsolutePath());
                }

                byte[] firstFourBytes = new byte[4];
                int headerBytesRead = 0;

                try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile), IO_BUFFER_SIZE)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        checkCancelled(cancelToken);
                        if (headerBytesRead < 4) {
                            int toCopy = Math.min(4 - headerBytesRead, read);
                            System.arraycopy(buffer, 0, firstFourBytes, headerBytesRead, toCopy);
                            headerBytesRead += toCopy;
                        }
                        fos.write(buffer, 0, read);
                        totalExtractedBytes += read;
                    }
                }

                if (shouldMarkExecutable(relativePath, firstFourBytes, headerBytesRead)) {
                    applyExecutablePermissions(outFile);
                    configuredExecutables.add(relativePath);
                }

                extractedFilesCount++;
                notifyProgress(listener, countingStream.getBytesRead(), totalZipBytes, relativePath);
                zis.closeEntry();
            }
        }

        // Если префикс не был известен заранее (однопроходный режим), проверяем и сдвигаем вложенные папки на диске
        if (precomputedPrefix == null) {
            String diskShifted = flattenNestedGameRootIfNeeded(instanceDir, configuredExecutables);
            if (!diskShifted.isEmpty()) {
                activePrefix = diskShifted;
            }
        }

        // Гарантируем, что основные бинарники в корне инстанса имеют права на исполнение
        ensureCoreBinariesExecutable(instanceDir, configuredExecutables);
        validateExtractedInstance(instanceDir);

        return new InstallResult(instanceDir, extractedFilesCount, totalExtractedBytes, activePrefix, configuredExecutables);
    }

    /**
     * Если после распаковки файлы ProjectZomboid64 и media/ оказались внутри одной или нескольких
     * лишних вложенных папок (например, instanceDir/projectzomboid/ProjectZomboid64),
     * автоматически переносит содержимое игрового корня напрямую в instanceDir и удаляет пустые обертки.
     */
    public String flattenNestedGameRootIfNeeded(File instanceDir, List<String> executableFilesList) throws IOException {
        File currentRoot = findGameRootDirectory(instanceDir, 0, 5);
        if (currentRoot == null || currentRoot.equals(instanceDir)) {
            return "";
        }

        String instanceCanonical = instanceDir.getCanonicalPath();
        String gameRootCanonical = currentRoot.getCanonicalPath();
        String relativeNestedPrefix = gameRootCanonical.substring(instanceCanonical.length());
        relativeNestedPrefix = normalizeZipPath(stripLeadingSlashes(relativeNestedPrefix));
        if (!relativeNestedPrefix.endsWith("/")) {
            relativeNestedPrefix += "/";
        }

        File[] children = currentRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                File target = new File(instanceDir, child.getName());
                moveOrMergeFileTree(child, target);
            }
        }

        // Удаляем опустевшие промежуточные директории
        String firstSegment = relativeNestedPrefix.split("/")[0];
        File topWrapperDir = new File(instanceDir, firstSegment);
        if (topWrapperDir.exists() && topWrapperDir.isDirectory()) {
            deleteEmptyDirectoriesRecursively(topWrapperDir);
        }

        // Обновляем список исполняемых файлов с учетом сдвига корня
        if (executableFilesList != null && !executableFilesList.isEmpty()) {
            List<String> updated = new ArrayList<>(executableFilesList.size());
            for (String oldPath : executableFilesList) {
                if (oldPath.startsWith(relativeNestedPrefix)) {
                    updated.add(oldPath.substring(relativeNestedPrefix.length()));
                } else {
                    updated.add(oldPath);
                }
            }
            executableFilesList.clear();
            executableFilesList.addAll(updated);
        }

        ensureCoreBinariesExecutable(instanceDir, executableFilesList);
        return relativeNestedPrefix;
    }

    /**
     * Рекурсивно ищет директорию, содержащую ProjectZomboid64 и папку media/ (до глубины maxDepth).
     */
    private File findGameRootDirectory(File dir, int currentDepth, int maxDepth) {
        if (dir == null || !dir.isDirectory() || currentDepth > maxDepth) {
            return null;
        }
        File pzBinary = new File(dir, PZ_MAIN_BINARY);
        File mediaDir = new File(dir, "media");
        if (pzBinary.isFile() && mediaDir.isDirectory()) {
            return dir;
        }
        if (pzBinary.isFile()) {
            return dir;
        }
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File sub : subdirs) {
                if ("media".equals(sub.getName()) || "jre64".equals(sub.getName()) || "launcher".equals(sub.getName())) {
                    continue;
                }
                File found = findGameRootDirectory(sub, currentDepth + 1, maxDepth);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void moveOrMergeFileTree(File source, File destination) throws IOException {
        if (source.equals(destination)) {
            return;
        }
        if (source.isDirectory()) {
            if (!destination.exists() && source.renameTo(destination)) {
                return;
            }
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IOException("Не удалось создать папку при переносе корня: " + destination);
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    moveOrMergeFileTree(child, new File(destination, child.getName()));
                }
            }
            source.delete();
        } else {
            if (destination.exists()) {
                destination.delete();
            }
            if (!source.renameTo(destination)) {
                copyFile(source, destination);
                if (source.canExecute()) {
                    applyExecutablePermissions(destination);
                }
                source.delete();
            }
        }
    }

    private boolean deleteEmptyDirectoriesRecursively(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteEmptyDirectoriesRecursively(child);
                }
            }
        }
        File[] remaining = dir.listFiles();
        if (remaining == null || remaining.length == 0) {
            return dir.delete();
        }
        return false;
    }

    /**
     * Проверяет, нужно ли выставлять файлу права setExecutable(true):
     * - Известные бинарники ProjectZomboid64, ProjectZomboid32, jre64/bin/*
     * - Скрипты *.sh
     * - Нативные библиотеки *.so, *.so.*, *.dylib
     * - Файлы с сигнатурой ELF (0x7F 'E' 'L' 'F') или shebang ('#!')
     */
    private boolean shouldMarkExecutable(String relativePath, byte[] firstFourBytes, int headerBytesRead) {
        if (KNOWN_EXECUTABLES.contains(relativePath)) {
            return true;
        }
        String fileName = new File(relativePath).getName();
        if (KNOWN_EXECUTABLES.contains(fileName)) {
            return true;
        }
        if (relativePath.startsWith("jre64/bin/") || relativePath.startsWith("linux64/")) {
            return true;
        }
        if (fileName.endsWith(".sh") || fileName.endsWith(".so") || fileName.contains(".so.") || fileName.endsWith(".dylib")) {
            return true;
        }
        // Проверка ELF-сигнатуры: 0x7F 'E' 'L' 'F'
        if (headerBytesRead >= 4
                && firstFourBytes[0] == 0x7F
                && firstFourBytes[1] == 'E'
                && firstFourBytes[2] == 'L'
                && firstFourBytes[3] == 'F') {
            return true;
        }
        // Проверка shebang: '#!'
        if (headerBytesRead >= 2
                && firstFourBytes[0] == '#'
                && firstFourBytes[1] == '!') {
            return true;
        }
        return false;
    }

    /**
     * Выставляет права чтения и исполнения (chmod +rx) на целевой бинарный файл.
     */
    private void applyExecutablePermissions(File file) {
        if (file != null && file.exists()) {
            file.setReadable(true, false);
            file.setExecutable(true, false);
        }
    }

    private void ensureCoreBinariesExecutable(File instanceDir, List<String> configuredExecutables) {
        for (String relPath : KNOWN_EXECUTABLES) {
            File f = new File(instanceDir, relPath);
            if (f.isFile()) {
                applyExecutablePermissions(f);
                if (configuredExecutables != null && !configuredExecutables.contains(relPath)) {
                    configuredExecutables.add(relPath);
                }
            }
        }
    }

    /**
     * Проверяет, что после распаковки в корне инстанса присутствуют обязательные файлы ProjectZomboid64 и media/.
     */
    private void validateExtractedInstance(File instanceDir) throws InvalidGameArchiveException {
        File mainBinary = new File(instanceDir, PZ_MAIN_BINARY);
        File mediaFolder = new File(instanceDir, "media");
        if (!mainBinary.isFile() || !mediaFolder.isDirectory()) {
            throw new InvalidGameArchiveException(
                    "После распаковки в корне инстанса отсутствуют обязательные файлы Linux-версии (" +
                    PZ_MAIN_BINARY + " и папка media/). Проверьте целостность исходного архива.");
        }
        applyExecutablePermissions(mainBinary);
    }

    // ============================================================================================
    // 3. ПРЯМАЯ ЗАГРУЗКА ИГРЫ ИЗ STEAM (DEPOT 108603) И СКАЧИВАНИЕ МОДОВ ИЗ STEAM WORKSHOP ПО ID
    // ============================================================================================

    /**
     * Получает метаданные мода из Steam Workshop по его ID через публичный Steamworks Web API:
     * POST https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/
     * (Документация: https://partner.steamgames.com/doc/webapi/isteamremotestorage)
     */
    public WorkshopModInfo fetchWorkshopModDetails(String rawWorkshopId) throws IOException {
        String cleanId = extractNumericWorkshopId(rawWorkshopId);
        if (cleanId.isEmpty()) {
            throw new IllegalArgumentException("Некорректный ID мода Steam Workshop: " + rawWorkshopId);
        }

        URL url = new URL("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            String postBody = "itemcount=1&publishedfileids%5B0%5D=" + URLEncoder.encode(cleanId, "UTF-8");
            byte[] bodyBytes = postBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Ошибка ответа Steam API (HTTP " + responseCode + ") при запросе мода " + cleanId);
            }

            String jsonResponse = readStreamAsString(conn.getInputStream());
            try {
                JSONObject root = new JSONObject(jsonResponse);
                JSONObject responseObj = root.getJSONObject("response");
                JSONArray detailsArray = responseObj.getJSONArray("publishedfiledetails");
                if (detailsArray.length() == 0) {
                    throw new IOException("Мод с ID " + cleanId + " не найден в Steam Workshop.");
                }
                JSONObject item = detailsArray.getJSONObject(0);
                int resultStatus = item.optInt("result", 0);
                if (resultStatus != 1) {
                    throw new IOException("Steam Workshop вернул код ошибки " + resultStatus + " для ID " + cleanId);
                }

                String title = item.optString("title", "Workshop_Mod_" + cleanId);
                long fileSize = item.optLong("file_size", 0L);
                String fileUrl = item.optString("file_url", "");
                String previewUrl = item.optString("preview_url", "");
                int consumerAppId = item.optInt("consumer_app_id", PZ_STEAM_APP_ID);

                return new WorkshopModInfo(cleanId, title, fileSize, fileUrl, previewUrl, consumerAppId);
            } catch (Exception jsonEx) {
                if (jsonEx instanceof IOException) {
                    throw (IOException) jsonEx;
                }
                throw new IOException("Ошибка разбора ответа Steam API: " + jsonEx.getMessage(), jsonEx);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Скачивает и устанавливает мод из Steam Workshop по его ID без ПК.
     *
     * Алгоритм работы (согласно руководству https://www.reddit.com/r/zomdroid/comments/1rtjsj3/how_to_download_pz_game_files_and_workshop_mods/
     * и релизу Zomdroid v1.4.5 https://www.reddit.com/r/zomdroid/comments/1tzlz0b/version_v145_steam_game_mod_downloader_new/):
     * 1. Запрашивает метаданные мода через ISteamRemoteStorage/GetPublishedFileDetails/v1.
     * 2. Если для мода доступен прямой UGC URL (file_url), скачивает архив напрямую по HTTPS и распаковывает
     *    в папку модов инстанса (<instanceDir>/Zomboid/mods/), а также сохраняет ZIP в Downloads/zomdroid/.
     * 3. Если мод распространяется через SteamPipe Depot UGC (пустой file_url), запускает встроенный
     *    клиент DepotDownloader в анонимном режиме:
     *    `depotdownloader -app 108600 -pubfile <MOD_ID> -dir <targetDir>`
     *    и автоматически нормализует регистр путей и структуру папок `mods/<ModName>/`.
     */
    public Future<?> downloadWorkshopModByIdAsync(final String rawWorkshopId,
                                                  final File instanceModsDir,
                                                  final File depotDownloaderBinary,
                                                  final AtomicBoolean cancelToken,
                                                  final InstallProgressListener listener) {
        return executorService.submit(() -> {
            try {
                String cleanId = extractNumericWorkshopId(rawWorkshopId);
                if (cleanId.isEmpty()) {
                    throw new IllegalArgumentException("Введите корректный числовой ID мода Steam Workshop.");
                }

                notifyPhase(listener, "Запрос метаданных мода #" + cleanId + " в Steam Workshop...");
                WorkshopModInfo modInfo = fetchWorkshopModDetails(cleanId);

                if (!instanceModsDir.exists() && !instanceModsDir.mkdirs()) {
                    throw new IOException("Не удалось создать директорию модов: " + instanceModsDir.getAbsolutePath());
                }

                if (modInfo.fileUrl != null && !modInfo.fileUrl.isEmpty()) {
                    notifyPhase(listener, "Скачивание мода «" + modInfo.title + "» напрямую из Steam UGC CDN...");
                    File tempZip = new File(instanceModsDir, "workshop_" + cleanId + ".zip");
                    downloadUrlToFile(modInfo.fileUrl, tempZip, modInfo.fileSize, cancelToken, listener);
                    InstallResult result = extractWorkshopModArchive(tempZip, instanceModsDir, cancelToken, listener);
                    notifyCompleted(listener, result);
                } else {
                    notifyPhase(listener, "Загрузка мода «" + modInfo.title + "» (#" + cleanId + ") через встроенный Steam-клиент...");
                    InstallResult result = executeDepotDownloaderForWorkshopMod(
                            cleanId, modInfo.title, instanceModsDir, depotDownloaderBinary, cancelToken, listener);
                    notifyCompleted(listener, result);
                }
            } catch (Exception e) {
                notifyError(listener, e);
            }
        });
    }

    /**
     * Запускает прямую загрузку Linux-версии Project Zomboid (AppID 108600, Depot 108603)
     * напрямую на смартфон без ПК (с поддержкой выбора ветки Build 41 / Build 42 Unstable и Manifest ID,
     * согласно https://www.reddit.com/r/zomdroid/comments/1o5qo06/guide_creating_a_zip_for_the_zomdroid_launcher/
     * и https://steamdb.info/depot/108603/manifests/).
     */
    public Future<?> downloadSteamGameDirectlyAsync(final String steamUsername,
                                                    final String steamPassword,
                                                    final String branchName,
                                                    final String manifestId,
                                                    final File instanceDir,
                                                    final File depotDownloaderBinary,
                                                    final AtomicBoolean cancelToken,
                                                    final InstallProgressListener listener) {
        return executorService.submit(() -> {
            try {
                if (steamUsername == null || steamUsername.trim().isEmpty()
                        || steamPassword == null || steamPassword.isEmpty()) {
                    throw new IllegalArgumentException("Для загрузки Project Zomboid из Steam необходимы логин и пароль аккаунта с купленной игрой.");
                }
                if (depotDownloaderBinary == null || !depotDownloaderBinary.exists()) {
                    throw new IOException("Бинарный модуль Steam-клиента (DepotDownloader) не найден по пути: " + depotDownloaderBinary);
                }
                applyExecutablePermissions(depotDownloaderBinary);

                if (!instanceDir.exists() && !instanceDir.mkdirs()) {
                    throw new IOException("Не удалось создать папку инстанса: " + instanceDir.getAbsolutePath());
                }

                List<String> command = new ArrayList<>();
                command.add(depotDownloaderBinary.getAbsolutePath());
                command.add("-app");
                command.add(String.valueOf(PZ_STEAM_APP_ID));
                command.add("-depot");
                command.add(String.valueOf(PZ_LINUX_DEPOT_ID));

                if (manifestId != null && !manifestId.trim().isEmpty()) {
                    command.add("-manifest");
                    command.add(manifestId.trim());
                }
                if (branchName != null && !branchName.trim().isEmpty() && !"public".equalsIgnoreCase(branchName.trim())) {
                    command.add("-beta");
                    command.add(branchName.trim());
                }

                command.add("-dir");
                command.add(instanceDir.getAbsolutePath());
                command.add("-username");
                command.add(steamUsername.trim());
                command.add("-password");
                command.add(steamPassword);

                notifyPhase(listener, "Авторизация в Steam (подтвердите вход в мобильном приложении Steam Guard)...");
                runExternalDownloaderProcess(command, instanceDir, cancelToken, listener);

                List<String> configuredExecs = new ArrayList<>();
                String shiftedPrefix = flattenNestedGameRootIfNeeded(instanceDir, configuredExecs);
                ensureCoreBinariesExecutable(instanceDir, configuredExecs);
                validateExtractedInstance(instanceDir);

                long totalBytes = computeDirectorySize(instanceDir);
                int totalFiles = countFilesInDirectory(instanceDir);
                InstallResult result = new InstallResult(instanceDir, totalFiles, totalBytes, shiftedPrefix, configuredExecs);
                notifyCompleted(listener, result);
            } catch (Exception e) {
                notifyError(listener, e);
            }
        });
    }

    /**
     * Распаковывает архив мода и нормализует иерархию папок (убирая лишние обертки Content/mods/ или Workshop/),
     * чтобы папка с файлом mod.info оказалась непосредственно внутри директории модов.
     */
    public InstallResult extractWorkshopModArchive(File modZipFile,
                                                   File targetModsDir,
                                                   AtomicBoolean cancelToken,
                                                   InstallProgressListener listener) throws IOException {
        File stagingDir = new File(targetModsDir, "_staging_" + System.currentTimeMillis());
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw new IOException("Не удалось создать временную директорию для распаковки мода: " + stagingDir);
        }

        int extractedCount = 0;
        long extractedBytes = 0L;
        byte[] buffer = new byte[IO_BUFFER_SIZE];
        String canonicalStagingRoot = stagingDir.getCanonicalPath() + File.separator;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(modZipFile), IO_BUFFER_SIZE), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                checkCancelled(cancelToken);
                String normPath = normalizeZipPath(entry.getName());
                if (normPath.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                File outFile = new File(stagingDir, normPath);
                if (!outFile.getCanonicalPath().startsWith(canonicalStagingRoot)) {
                    throw new SecurityException("Обнаружен небезопасный путь в архиве мода: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    zis.closeEntry();
                    continue;
                }
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(outFile), IO_BUFFER_SIZE)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        checkCancelled(cancelToken);
                        fos.write(buffer, 0, read);
                        extractedBytes += read;
                    }
                }
                extractedCount++;
                notifyProgress(listener, extractedBytes, modZipFile.length(), normPath);
                zis.closeEntry();
            }
        }

        // Ищем все папки модов, содержащие mod.info, и переносим их в targetModsDir
        List<File> modInfoDirs = new ArrayList<>();
        collectModInfoDirectories(stagingDir, modInfoDirs);

        if (!modInfoDirs.isEmpty()) {
            for (File modDir : modInfoDirs) {
                File destModDir = new File(targetModsDir, modDir.getName());
                moveOrMergeFileTree(modDir, destModDir);
            }
            deleteDirectoryRecursively(stagingDir);
        } else {
            // Если mod.info лежит на нестандартном уровне, переносим содержимое как есть
            File[] children = stagingDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    moveOrMergeFileTree(child, new File(targetModsDir, child.getName()));
                }
            }
            deleteDirectoryRecursively(stagingDir);
        }

        return new InstallResult(targetModsDir, extractedCount, extractedBytes, "mods/", Collections.emptyList());
    }

    private InstallResult executeDepotDownloaderForWorkshopMod(String cleanModId,
                                                               String modTitle,
                                                               File targetModsDir,
                                                               File depotDownloaderBinary,
                                                               AtomicBoolean cancelToken,
                                                               InstallProgressListener listener) throws IOException {
        if (depotDownloaderBinary == null || !depotDownloaderBinary.exists()) {
            throw new IOException(
                    "Мод «" + modTitle + "» (#" + cleanModId + ") использует защищенный протокол SteamPipe UGC. " +
                    "Укажите путь к бинарнику DepotDownloader в настройках Zomdroid.");
        }
        applyExecutablePermissions(depotDownloaderBinary);

        File stagingDir = new File(targetModsDir, "workshop_" + cleanModId);
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw new IOException("Не удалось создать папку для загрузки мода: " + stagingDir.getAbsolutePath());
        }

        List<String> command = Arrays.asList(
                depotDownloaderBinary.getAbsolutePath(),
                "-app", String.valueOf(PZ_STEAM_APP_ID),
                "-pubfile", cleanModId,
                "-dir", stagingDir.getAbsolutePath()
        );

        runExternalDownloaderProcess(command, stagingDir, cancelToken, listener);

        List<File> modInfoDirs = new ArrayList<>();
        collectModInfoDirectories(stagingDir, modInfoDirs);
        for (File modDir : modInfoDirs) {
            File destModDir = new File(targetModsDir, modDir.getName());
            moveOrMergeFileTree(modDir, destModDir);
        }

        long bytes = computeDirectorySize(targetModsDir);
        int files = countFilesInDirectory(targetModsDir);
        return new InstallResult(targetModsDir, files, bytes, "mods/", Collections.emptyList());
    }

    private void runExternalDownloaderProcess(List<String> command,
                                              File workingDir,
                                              AtomicBoolean cancelToken,
                                              InstallProgressListener listener) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        Pattern percentPattern = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%");

        try (InputStream stdout = process.getInputStream();
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelToken != null && cancelToken.get()) {
                    process.destroyForcibly();
                    throw new IOException("Операция отменена пользователем.");
                }
                Matcher m = percentPattern.matcher(line);
                if (m.find()) {
                    try {
                        double pct = Double.parseDouble(m.group(1));
                        notifyProgress(listener, (long) (pct * 100), 10000L, line.trim());
                    } catch (NumberFormatException ignored) {
                        notifyPhase(listener, line.trim());
                    }
                } else if (!line.trim().isEmpty()) {
                    notifyPhase(listener, line.trim());
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Процесс загрузки завершился с кодом ошибки: " + exitCode);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Загрузка была прервана", ie);
        }
    }

    private void collectModInfoDirectories(File root, List<File> outDirs) {
        if (root == null || !root.isDirectory()) {
            return;
        }
        File modInfo = new File(root, "mod.info");
        if (modInfo.isFile()) {
            outDirs.add(root);
            return;
        }
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                collectModInfoDirectories(child, outDirs);
            }
        }
    }

    // ============================================================================================
    // ВСПОМОГАТЕЛЬНЫЕ ПОТОКОВЫЕ И ФАЙЛОВЫЕ МЕТОДЫ
    // ============================================================================================

    /**
     * Извлекает числовой ID мода из строки (поддерживает как прямой ID "2875848298",
     * так и полную ссылку вида "https://steamcommunity.com/sharedfiles/filedetails/?id=2875848298").
     */
    public static String extractNumericWorkshopId(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        Matcher urlMatcher = Pattern.compile("[?&]id=(\\d+)").matcher(trimmed);
        if (urlMatcher.find()) {
            return urlMatcher.group(1);
        }
        Matcher digitsMatcher = Pattern.compile("^(\\d{5,15})$").matcher(trimmed);
        if (digitsMatcher.find()) {
            return digitsMatcher.group(1);
        }
        return "";
    }

    private static String normalizeZipPath(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String p = rawPath.replace('\\', '/').trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return stripLeadingSlashes(p);
    }

    private static String stripLeadingSlashes(String path) {
        int i = 0;
        while (i < path.length() && path.charAt(i) == '/') {
            i++;
        }
        return i > 0 ? path.substring(i) : path;
    }

    private static void checkCancelled(AtomicBoolean cancelToken) throws IOException {
        if (cancelToken != null && cancelToken.get()) {
            throw new IOException("Установка отменена пользователем.");
        }
    }

    private void downloadUrlToFile(String urlString,
                                   File destination,
                                   long expectedBytes,
                                   AtomicBoolean cancelToken,
                                   InstallProgressListener listener) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Не удалось скачать файл (HTTP " + code + "): " + urlString);
            }
            long total = conn.getContentLengthLong();
            if (total <= 0) {
                total = expectedBytes;
            }
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            long downloaded = 0L;
            try (InputStream is = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE);
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(destination), IO_BUFFER_SIZE)) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    checkCancelled(cancelToken);
                    os.write(buffer, 0, read);
                    downloaded += read;
                    notifyProgress(listener, downloaded, total, destination.getName());
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[IO_BUFFER_SIZE];
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteDirectoryRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryRecursively(child);
                }
            }
        }
        dir.delete();
    }

    private static long computeDirectorySize(File dir) {
        if (dir == null || !dir.exists()) {
            return 0L;
        }
        if (dir.isFile()) {
            return dir.length();
        }
        long total = 0L;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                total += computeDirectorySize(c);
            }
        }
        return total;
    }

    private static int countFilesInDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        if (dir.isFile()) {
            return 1;
        }
        int count = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                count += countFilesInDirectory(c);
            }
        }
        return count;
    }

    private static String readStreamAsString(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private long queryUriSize(ContentResolver resolver, Uri uri) {
        try (android.database.Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                    return cursor.getLong(sizeIdx);
                }
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private void notifyPhase(final InstallProgressListener listener, final String phase) {
        if (listener == null) {
            return;
        }
        if (mainHandler != null) {
            mainHandler.post(() -> listener.onPhaseChanged(phase));
        } else {
            listener.onPhaseChanged(phase);
        }
    }

    private void notifyProgress(final InstallProgressListener listener,
                                final long processed,
                                final long total,
                                final String currentFile) {
        if (listener == null) {
            return;
        }
        if (mainHandler != null) {
            mainHandler.post(() -> listener.onProgress(processed, total, currentFile));
        } else {
            listener.onProgress(processed, total, currentFile);
        }
    }

    private void notifyCompleted(final InstallProgressListener listener, final InstallResult result) {
        if (listener == null) {
            return;
        }
        if (mainHandler != null) {
            mainHandler.post(() -> listener.onCompleted(result));
        } else {
            listener.onCompleted(result);
        }
    }

    private void notifyError(final InstallProgressListener listener, final Exception error) {
        if (listener == null) {
            return;
        }
        if (mainHandler != null) {
            mainHandler.post(() -> listener.onError(error));
        } else {
            listener.onError(error);
        }
    }

    /**
     * Поток-счетчик прочитанных байтов для точного отображения прогресса по мере чтения архива.
     */
    private static class CountingInputStream extends FilterInputStream {
        private long bytesRead = 0L;

        protected CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int r = super.read();
            if (r != -1) {
                bytesRead++;
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            if (s > 0) {
                bytesRead += s;
            }
            return s;
        }

        public long getBytesRead() {
            return bytesRead;
        }
    }

    /**
     * Обертка InputStream, возвращающая сначала буфер уже считанных байтов сигнатуры PK\x03\x04,
     * а затем продолжающая чтение из основного потока.
     */
    private static class PrependInputStream extends InputStream {
        private final byte[] prefix;
        private int prefixOffset = 0;
        private final InputStream tail;

        public PrependInputStream(byte[] prefix, InputStream tail) {
            this.prefix = prefix;
            this.tail = tail;
        }

        @Override
        public int read() throws IOException {
            if (prefixOffset < prefix.length) {
                return prefix[prefixOffset++] & 0xFF;
            }
            return tail.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (prefixOffset < prefix.length) {
                int toCopy = Math.min(len, prefix.length - prefixOffset);
                System.arraycopy(prefix, prefixOffset, b, off, toCopy);
                prefixOffset += toCopy;
                return toCopy;
            }
            return tail.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            tail.close();
        }
    }
}
