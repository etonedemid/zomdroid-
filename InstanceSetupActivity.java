package com.zomdroid.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.zomdroid.installer.GameInstallerManager;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Экран и диалоговый модуль создания игрового инстанса без ПК и загрузки модов из Steam Workshop для Zomdroid.
 *
 * Реализует:
 * 1. Выбор источника установки инстанса без использования ПК:
 *    - Прямая загрузка Linux-версии из Steam (AppID 108600, Depot 108603, выбор ветки B41/B42 и Manifest ID).
 *    - Автономная распаковка оффлайн-инсталлятора GOG (.sh на базе MojoSetup, извлечение data/noarch/game/).
 *    - Умный импорт .zip архива с автоматическим сдвигом лишней вложенной папки и выставлением прав setExecutable(true).
 * 2. Меню скачивания модов из Steam Workshop по ID или ссылке (анонимно через ISteamRemoteStorage и UGC/DepotDownloader).
 *
 * Источники:
 * - Zomdroid: https://github.com/liamelui/zomdroid и https://github.com/etonedemid/zomdroid-
 * - Гайд по созданию ZIP-архива для Zomdroid: https://www.reddit.com/r/zomdroid/comments/1o5qo06/guide_creating_a_zip_for_the_zomdroid_launcher/
 * - Загрузка игры и модов напрямую на смартфон: https://www.reddit.com/r/zomdroid/comments/1rtjsj3/how_to_download_pz_game_files_and_workshop_mods/
 * - Релиз встроенного загрузчика Steam/Workshop в Zomdroid v1.4.5: https://www.reddit.com/r/zomdroid/comments/1tzlz0b/version_v145_steam_game_mod_downloader_new/
 * - Релиз поддержки GOG в Zomdroid v1.5.0: https://www.reddit.com/r/zomdroid/comments/1wj1y3s/version_v150_server_hosting_native_macos/
 */
public class InstanceSetupActivity extends Activity {

    private static final int REQUEST_CODE_PICK_GOG_SH = 1001;
    private static final int REQUEST_CODE_PICK_GAME_ZIP = 1002;

    /** Предустановленные манифесты из https://steamdb.info/depot/108603/manifests/ */
    private static final String[] BUILD_PRESETS = new String[]{
            "Build 41.78.16 Stable (legacy41 — 8887512142279836582)",
            "Build 42.7 Unstable (manifest — 7735218517840308728)",
            "Build 42.19 Unstable (manifest — 7619260596173999460)",
            "Build 42 Latest Unstable (manifest — 1215816700457910954)"
    };

    private static final String[] RENDERER_PRESETS = new String[]{
            "GL4ES (Рекомендуется для Build 41 на всех GPU)",
            "ZINK_ZFA (Рекомендуется для Build 42 на Snapdragon / Adreno)",
            "NG_GL4ES (Для Build 42 на Mali / MediaTek без ZINK)"
    };

    private GameInstallerManager installerManager;
    private final AtomicBoolean currentTaskCancelToken = new AtomicBoolean(false);

    // Элементы UI создания инстанса
    private EditText instanceNameInput;
    private Spinner buildPresetSpinner;
    private Spinner rendererSpinner;
    private RadioGroup sourceRadioGroup;
    private RadioButton radioSteamDirect;
    private RadioButton radioGogSh;
    private RadioButton radioZipImport;

    // Панель настроек прямой загрузки из Steam
    private LinearLayout steamCredentialsPanel;
    private EditText steamUsernameInput;
    private EditText steamPasswordInput;
    private EditText steamBranchInput;
    private EditText steamManifestInput;

    // Панель выбора локального файла (.sh / .zip)
    private LinearLayout filePickerPanel;
    private TextView selectedFileLabel;
    private Button pickFileButton;
    private Uri selectedArchiveUri;

    // Панель статуса и прогресса
    private ProgressBar installProgressBar;
    private TextView statusPhaseText;
    private TextView statusDetailText;
    private Button startInstallButton;
    private Button cancelInstallButton;
    private Button openWorkshopDialogButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installerManager = new GameInstallerManager(this);
        setContentView(buildCompleteLauncherUi());
        applyPresetSelectionLogic();
    }

    /**
     * Программно формирует адаптивный UI экрана создания инстанса и панели Steam Workshop,
     * чтобы модуль компилировался без внешних XML-ресурсов в любом форке Zomdroid.
     */
    private View buildCompleteLauncherUi() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);
        rootScroll.setBackgroundColor(Color.parseColor("#121619"));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        container.setPadding(pad, pad, pad, pad);

        // Заголовок экрана
        TextView titleView = new TextView(this);
        titleView.setText("Zomdroid — Создание инстанса (без ПК)");
        titleView.setTextColor(Color.parseColor("#E6EDF3"));
        titleView.setTextSize(20f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText("Автономная установка Linux-версии Project Zomboid (Steam / GOG .sh / Умный ZIP)");
        subtitleView.setTextColor(Color.parseColor("#8B949E"));
        subtitleView.setTextSize(13f);
        subtitleView.setPadding(0, dpToPx(4), 0, dpToPx(16));
        container.addView(subtitleView);

        // 1. Поле имени инстанса
        container.addView(createSectionLabel("1. Название инстанса и пресет сборки"));
        instanceNameInput = createStyledEditText("Например: PZ_Build42_Survival", InputType.TYPE_CLASS_TEXT);
        instanceNameInput.setText("ProjectZomboid_Main");
        container.addView(instanceNameInput);

        // 2. Выбор версии (Build 41 / Build 42) и рендерера
        buildPresetSpinner = new Spinner(this);
        ArrayAdapter<String> buildAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, BUILD_PRESETS);
        buildPresetSpinner.setAdapter(buildAdapter);
        container.addView(buildPresetSpinner, createMarginParams(0, dpToPx(8), 0, dpToPx(8)));

        rendererSpinner = new Spinner(this);
        ArrayAdapter<String> rendererAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, RENDERER_PRESETS);
        rendererSpinner.setAdapter(rendererAdapter);
        container.addView(rendererSpinner, createMarginParams(0, 0, 0, dpToPx(16)));

        // 3. Выбор источника создания инстанса
        container.addView(createSectionLabel("2. Источник установки файлов игры"));

        sourceRadioGroup = new RadioGroup(this);
        sourceRadioGroup.setOrientation(LinearLayout.VERTICAL);

        radioGogSh = new RadioButton(this);
        radioGogSh.setText("Установка из оффлайн-инсталлятора GOG (.sh, MojoSetup)");
        radioGogSh.setTextColor(Color.parseColor("#E6EDF3"));
        radioGogSh.setId(View.generateViewId());

        radioZipImport = new RadioButton(this);
        radioZipImport.setText("Умный импорт .zip архива (авто-сдвиг вложенных папок)");
        radioZipImport.setTextColor(Color.parseColor("#E6EDF3"));
        radioZipImport.setId(View.generateViewId());

        radioSteamDirect = new RadioButton(this);
        radioSteamDirect.setText("Прямая загрузка из Steam (Depot 108603, без ПК)");
        radioSteamDirect.setTextColor(Color.parseColor("#E6EDF3"));
        radioSteamDirect.setId(View.generateViewId());

        sourceRadioGroup.addView(radioGogSh);
        sourceRadioGroup.addView(radioZipImport);
        sourceRadioGroup.addView(radioSteamDirect);
        radioGogSh.setChecked(true);
        container.addView(sourceRadioGroup, createMarginParams(0, dpToPx(4), 0, dpToPx(12)));

        // Панель выбора файла для GOG .sh и .zip
        filePickerPanel = new LinearLayout(this);
        filePickerPanel.setOrientation(LinearLayout.VERTICAL);
        filePickerPanel.setBackgroundColor(Color.parseColor("#1B2228"));
        int cardPad = dpToPx(14);
        filePickerPanel.setPadding(cardPad, cardPad, cardPad, cardPad);

        selectedFileLabel = new TextView(this);
        selectedFileLabel.setText("Файл не выбран. Выберите project_zomboid_*.sh или архив .zip");
        selectedFileLabel.setTextColor(Color.parseColor("#C9D1D9"));
        selectedFileLabel.setTextSize(13f);
        filePickerPanel.addView(selectedFileLabel);

        pickFileButton = new Button(this);
        pickFileButton.setText("Выбрать файл на смартфоне");
        pickFileButton.setOnClickListener(v -> triggerFilePickerForCurrentMode());
        filePickerPanel.addView(pickFileButton, createMarginParams(0, dpToPx(8), 0, 0));
        container.addView(filePickerPanel, createMarginParams(0, 0, 0, dpToPx(12)));

        // Панель учетных данных Steam для прямой загрузки
        steamCredentialsPanel = new LinearLayout(this);
        steamCredentialsPanel.setOrientation(LinearLayout.VERTICAL);
        steamCredentialsPanel.setBackgroundColor(Color.parseColor("#1B2228"));
        steamCredentialsPanel.setPadding(cardPad, cardPad, cardPad, cardPad);
        steamCredentialsPanel.setVisibility(View.GONE);

        TextView steamHint = new TextView(this);
        steamHint.setText("Загрузка Linux-депо 108603 напрямую со серверов Steam (требуется подтверждение в мобильном приложении Steam Guard):");
        steamHint.setTextColor(Color.parseColor("#8B949E"));
        steamHint.setTextSize(12f);
        steamCredentialsPanel.addView(steamHint);

        steamUsernameInput = createStyledEditText("Логин Steam", InputType.TYPE_CLASS_TEXT);
        steamPasswordInput = createStyledEditText("Пароль Steam", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        steamBranchInput = createStyledEditText("Ветка (public / unstable / legacy41)", InputType.TYPE_CLASS_TEXT);
        steamBranchInput.setText("legacy41");
        steamManifestInput = createStyledEditText("Manifest ID (опционально, напр. 7735218517840308728)", InputType.TYPE_CLASS_NUMBER);

        steamCredentialsPanel.addView(steamUsernameInput, createMarginParams(0, dpToPx(6), 0, 0));
        steamCredentialsPanel.addView(steamPasswordInput, createMarginParams(0, dpToPx(6), 0, 0));
        steamCredentialsPanel.addView(steamBranchInput, createMarginParams(0, dpToPx(6), 0, 0));
        steamCredentialsPanel.addView(steamManifestInput, createMarginParams(0, dpToPx(6), 0, 0));
        container.addView(steamCredentialsPanel, createMarginParams(0, 0, 0, dpToPx(12)));

        // 4. Индикатор прогресса и кнопки управления
        installProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installProgressBar.setMax(100);
        installProgressBar.setProgress(0);
        container.addView(installProgressBar, createMarginParams(0, dpToPx(4), 0, dpToPx(6)));

        statusPhaseText = new TextView(this);
        statusPhaseText.setText("Готов к созданию инстанса.");
        statusPhaseText.setTextColor(Color.parseColor("#58A6FF"));
        statusPhaseText.setTextSize(13f);
        statusPhaseText.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(statusPhaseText);

        statusDetailText = new TextView(this);
        statusDetailText.setText("");
        statusDetailText.setTextColor(Color.parseColor("#8B949E"));
        statusDetailText.setTextSize(12f);
        container.addView(statusDetailText, createMarginParams(0, dpToPx(2), 0, dpToPx(12)));

        LinearLayout actionButtonsRow = new LinearLayout(this);
        actionButtonsRow.setOrientation(LinearLayout.HORIZONTAL);

        startInstallButton = new Button(this);
        startInstallButton.setText("Создать и распаковать инстанс");
        startInstallButton.setOnClickListener(v -> startInstanceInstallation());

        cancelInstallButton = new Button(this);
        cancelInstallButton.setText("Отмена");
        cancelInstallButton.setEnabled(false);
        cancelInstallButton.setOnClickListener(v -> {
            currentTaskCancelToken.set(true);
            statusPhaseText.setText("Отмена операции...");
        });

        LinearLayout.LayoutParams startBtnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        startBtnParams.setMarginEnd(dpToPx(8));
        actionButtonsRow.addView(startInstallButton, startBtnParams);
        actionButtonsRow.addView(cancelInstallButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(actionButtonsRow, createMarginParams(0, 0, 0, dpToPx(20)));

        // 5. Кнопка открытия меню скачивания модов из Steam Workshop по ID
        container.addView(createSectionLabel("3. Управление модами Steam Workshop"));
        openWorkshopDialogButton = new Button(this);
        openWorkshopDialogButton.setText("Скачать моды из Steam Workshop по ID (без входа в аккаунт)");
        openWorkshopDialogButton.setOnClickListener(v -> showWorkshopModDownloadDialog());
        container.addView(openWorkshopDialogButton);

        rootScroll.addView(container);
        return rootScroll;
    }

    private void applyPresetSelectionLogic() {
        sourceRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isSteam = (checkedId == radioSteamDirect.getId());
            steamCredentialsPanel.setVisibility(isSteam ? View.VISIBLE : View.GONE);
            filePickerPanel.setVisibility(isSteam ? View.GONE : View.VISIBLE);
            selectedArchiveUri = null;
            if (checkedId == radioGogSh.getId()) {
                selectedFileLabel.setText("Выберите оффлайн-инсталлятор GOG: project_zomboid_*.sh");
            } else if (checkedId == radioZipImport.getId()) {
                selectedFileLabel.setText("Выберите ZIP-архив с Linux-файлами Project Zomboid (.zip)");
            }
        });

        buildPresetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    steamBranchInput.setText("legacy41");
                    steamManifestInput.setText("8887512142279836582");
                    rendererSpinner.setSelection(0); // GL4ES для Build 41
                } else if (position == 1) {
                    steamBranchInput.setText("unstable");
                    steamManifestInput.setText("7735218517840308728");
                    rendererSpinner.setSelection(1); // ZINK_ZFA для Build 42.7
                } else if (position == 2) {
                    steamBranchInput.setText("unstable");
                    steamManifestInput.setText("7619260596173999460");
                    rendererSpinner.setSelection(1);
                } else {
                    steamBranchInput.setText("unstable");
                    steamManifestInput.setText("1215816700457910954");
                    rendererSpinner.setSelection(1);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void triggerFilePickerForCurrentMode() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        int requestCode = (sourceRadioGroup.getCheckedRadioButtonId() == radioGogSh.getId())
                ? REQUEST_CODE_PICK_GOG_SH
                : REQUEST_CODE_PICK_GAME_ZIP;
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            selectedArchiveUri = data.getData();
            selectedFileLabel.setText("Выбран файл: " + selectedArchiveUri.getLastPathSegment());
        }
    }

    /**
     * Запускает установку игрового инстанса из выбранного источника (GOG .sh, ZIP-архив или Steam Depot).
     */
    private void startInstanceInstallation() {
        String rawInstanceName = instanceNameInput.getText().toString().trim();
        if (rawInstanceName.isEmpty()) {
            Toast.makeText(this, "Введите название инстанса", Toast.LENGTH_SHORT).show();
            return;
        }
        String safeInstanceName = rawInstanceName.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        File instancesRoot = new File(getFilesDir(), "instances");
        File targetInstanceDir = new File(instancesRoot, safeInstanceName);

        currentTaskCancelToken.set(false);
        setInstallationUiBusy(true);

        GameInstallerManager.InstallProgressListener progressListener = new GameInstallerManager.InstallProgressListener() {
            @Override
            public void onPhaseChanged(String phaseDescription) {
                statusPhaseText.setText(phaseDescription);
            }

            @Override
            public void onProgress(long bytesProcessed, long totalBytes, String currentFile) {
                if (totalBytes > 0) {
                    int pct = (int) Math.min(100L, (bytesProcessed * 100L) / totalBytes);
                    installProgressBar.setIndeterminate(false);
                    installProgressBar.setProgress(pct);
                    statusDetailText.setText(String.format(Locale.US,
                            "%d%% (%.1f МБ / %.1f МБ) — %s",
                            pct,
                            bytesProcessed / (1024.0 * 1024.0),
                            totalBytes / (1024.0 * 1024.0),
                            currentFile));
                } else {
                    installProgressBar.setIndeterminate(true);
                    statusDetailText.setText(String.format(Locale.US,
                            "Обработано %.1f МБ — %s",
                            bytesProcessed / (1024.0 * 1024.0),
                            currentFile));
                }
            }

            @Override
            public void onCompleted(GameInstallerManager.InstallResult result) {
                setInstallationUiBusy(false);
                installProgressBar.setIndeterminate(false);
                installProgressBar.setProgress(100);
                String shiftNote = (result.strippedRootPrefix != null && !result.strippedRootPrefix.isEmpty())
                        ? " (авто-сдвиг корня: " + result.strippedRootPrefix + ")"
                        : "";
                statusPhaseText.setText("Инстанс успешно создан" + shiftNote);
                statusDetailText.setText(String.format(Locale.US,
                        "Файлов: %d | Размер: %.1f МБ | Бинарников с chmod +x: %d",
                        result.extractedFilesCount,
                        result.totalExtractedBytes / (1024.0 * 1024.0),
                        result.executableFilesConfigured.size()));
                Toast.makeText(InstanceSetupActivity.this,
                        "Инстанс готов к запуску: " + result.targetDirectory.getName(),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(Exception error) {
                setInstallationUiBusy(false);
                installProgressBar.setIndeterminate(false);
                statusPhaseText.setText("Ошибка установки");
                statusDetailText.setText(error.getMessage());
                new AlertDialog.Builder(InstanceSetupActivity.this)
                        .setTitle("Ошибка создания инстанса")
                        .setMessage(error.getMessage())
                        .setPositiveButton("ОК", null)
                        .show();
            }
        };

        int checkedId = sourceRadioGroup.getCheckedRadioButtonId();
        if (checkedId == radioGogSh.getId()) {
            if (selectedArchiveUri == null) {
                setInstallationUiBusy(false);
                Toast.makeText(this, "Сначала выберите файл инсталлятора GOG (.sh)", Toast.LENGTH_SHORT).show();
                return;
            }
            installerManager.installFromGogShAsync(
                    selectedArchiveUri, targetInstanceDir, currentTaskCancelToken, progressListener);

        } else if (checkedId == radioZipImport.getId()) {
            if (selectedArchiveUri == null) {
                setInstallationUiBusy(false);
                Toast.makeText(this, "Сначала выберите ZIP-архив игры (.zip)", Toast.LENGTH_SHORT).show();
                return;
            }
            installerManager.installFromZipUriAsync(
                    selectedArchiveUri, targetInstanceDir, currentTaskCancelToken, progressListener);

        } else if (checkedId == radioSteamDirect.getId()) {
            String user = steamUsernameInput.getText().toString().trim();
            String pass = steamPasswordInput.getText().toString();
            String branch = steamBranchInput.getText().toString().trim();
            String manifest = steamManifestInput.getText().toString().trim();
            File depotBinary = new File(getFilesDir(), "bin/DepotDownloader");

            installerManager.downloadSteamGameDirectlyAsync(
                    user, pass, branch, manifest, targetInstanceDir, depotBinary, currentTaskCancelToken, progressListener);
        }
    }

    /**
     * Открывает диалоговое окно скачивания и установки модов из Steam Workshop по ID (или URL)
     * с предпросмотром метаданных мода через Steam Web API.
     */
    public void showWorkshopModDownloadDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        dialogLayout.setPadding(pad, pad, pad, pad);

        TextView infoLabel = new TextView(this);
        infoLabel.setText("Введите ID мода из Steam Workshop (например, 2875848298) или полную ссылку на страницу мода. Вход в аккаунт Steam не требуется.");
        infoLabel.setTextSize(13f);
        dialogLayout.addView(infoLabel);

        final EditText workshopIdInput = createStyledEditText(
                "Workshop ID или URL (напр. 2875848298)", InputType.TYPE_CLASS_TEXT);
        dialogLayout.addView(workshopIdInput, createMarginParams(0, dpToPx(10), 0, dpToPx(10)));

        final TextView modPreviewText = new TextView(this);
        modPreviewText.setText("Статус: ожидание ввода ID мода...");
        modPreviewText.setTextSize(12f);
        dialogLayout.addView(modPreviewText);

        final ProgressBar modProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modProgressBar.setMax(100);
        dialogLayout.addView(modProgressBar, createMarginParams(0, dpToPx(8), 0, 0));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Скачивание модов Steam Workshop")
                .setView(dialogLayout)
                .setPositiveButton("Скачать и установить", null)
                .setNeutralButton("Проверить ID", null)
                .setNegativeButton("Закрыть", (d, which) -> currentTaskCancelToken.set(true))
                .create();

        dialog.setOnShowListener(dlg -> {
            Button checkBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button downloadBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

            checkBtn.setOnClickListener(v -> {
                final String rawInput = workshopIdInput.getText().toString().trim();
                modPreviewText.setText("Запрос информации о моде в Steam Workshop...");
                new Thread(() -> {
                    try {
                        GameInstallerManager.WorkshopModInfo info = installerManager.fetchWorkshopModDetails(rawInput);
                        runOnUiThread(() -> modPreviewText.setText(String.format(Locale.US,
                                "Найден мод: «%s» (ID: %s, Размер: %.2f МБ)",
                                info.title, info.publishedFileId, info.fileSize / (1024.0 * 1024.0))));
                    } catch (Exception e) {
                        runOnUiThread(() -> modPreviewText.setText("Ошибка: " + e.getMessage()));
                    }
                }).start();
            });

            downloadBtn.setOnClickListener(v -> {
                String rawInput = workshopIdInput.getText().toString().trim();
                String instanceName = instanceNameInput.getText().toString().trim();
                if (instanceName.isEmpty()) {
                    instanceName = "ProjectZomboid_Main";
                }
                File modsDir = new File(new File(getFilesDir(), "instances/" + instanceName), "Zomboid/mods");
                File depotBinary = new File(getFilesDir(), "bin/DepotDownloader");

                currentTaskCancelToken.set(false);
                downloadBtn.setEnabled(false);

                installerManager.downloadWorkshopModByIdAsync(
                        rawInput,
                        modsDir,
                        depotBinary,
                        currentTaskCancelToken,
                        new GameInstallerManager.InstallProgressListener() {
                            @Override
                            public void onPhaseChanged(String phaseDescription) {
                                modPreviewText.setText(phaseDescription);
                            }

                            @Override
                            public void onProgress(long bytesProcessed, long totalBytes, String currentFile) {
                                if (totalBytes > 0) {
                                    int pct = (int) Math.min(100L, (bytesProcessed * 100L) / totalBytes);
                                    modProgressBar.setIndeterminate(false);
                                    modProgressBar.setProgress(pct);
                                    modPreviewText.setText(pct + "% — " + currentFile);
                                } else {
                                    modProgressBar.setIndeterminate(true);
                                    modPreviewText.setText(currentFile);
                                }
                            }

                            @Override
                            public void onCompleted(GameInstallerManager.InstallResult result) {
                                downloadBtn.setEnabled(true);
                                modProgressBar.setIndeterminate(false);
                                modProgressBar.setProgress(100);
                                modPreviewText.setText("Мод успешно установлен в: " + result.targetDirectory.getAbsolutePath());
                                Toast.makeText(InstanceSetupActivity.this,
                                        "Мод добавлен! Активируйте его в меню Mods внутри игры.",
                                        Toast.LENGTH_LONG).show();
                            }

                            @Override
                            public void onError(Exception error) {
                                downloadBtn.setEnabled(true);
                                modProgressBar.setIndeterminate(false);
                                modPreviewText.setText("Ошибка загрузки мода: " + error.getMessage());
                            }
                        });
            });
        });

        dialog.show();
    }

    private void setInstallationUiBusy(boolean busy) {
        startInstallButton.setEnabled(!busy);
        cancelInstallButton.setEnabled(busy);
        pickFileButton.setEnabled(!busy);
        instanceNameInput.setEnabled(!busy);
        buildPresetSpinner.setEnabled(!busy);
        rendererSpinner.setEnabled(!busy);
        for (int i = 0; i < sourceRadioGroup.getChildCount(); i++) {
            sourceRadioGroup.getChildAt(i).setEnabled(!busy);
        }
    }

    private TextView createSectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#58A6FF"));
        tv.setTextSize(14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dpToPx(8), 0, dpToPx(6));
        return tv;
    }

    private EditText createStyledEditText(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#6E7681"));
        et.setTextColor(Color.parseColor("#E6EDF3"));
        et.setBackgroundColor(Color.parseColor("#21262D"));
        et.setInputType(inputType);
        int pad = dpToPx(10);
        et.setPadding(pad, pad, pad, pad);
        return et;
    }

    private LinearLayout.LayoutParams createMarginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
