package cn.yanyn.ylive;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Y Live - RTMP→HLV 穿透工具
 *
 * 版权所有 (C) 2026 晏阳技术组
 * 基于 GPLv3 协议开源
 */
public class YLiveApp extends Application {

    // ==================== UI组件 ====================
    private TextField rtmpPortField, streamNameField, subtitleTextField, fontPathField;
    private TextField hlsPortField, flvPortField, apiPortField;
    private TextField frpConfigPathField;
    private Label pushUrlLabel, rtmpStatusLabel, hlsUrlLabel, fontSizeLabel, connectionStatusLabel;
    private Label frpStatusLabel;
    private TextField streamKeyDisplayField;
    private Label flvUrlLabel;
    private Button startRtmpBtn, stopRtmpBtn, browseFontBtn, previewSubtitleBtn;
    private Button startTranscodeBtn, stopTranscodeBtn, browseFrpBtn, startFrpBtn, stopFrpBtn;
    private Button copyStreamKeyBtn, copyPushUrlBtn, copyHlsUrlBtn, copyFlvUrlBtn;
    private Button getStreamKeyBtn;
    private Slider fontSizeSlider;
    private ColorPicker fontColorPicker;
    private CheckBox shadowCheckBox;
    private TextArea logArea;
    private ProgressIndicator rtmpProgress;
    private Stage primaryStage;
    private double xOffset = 0;
    private double yOffset = 0;

    // ==================== 平台检测 ====================
    private String osName = System.getProperty("os.name").toLowerCase();
    private boolean isWindows = osName.contains("win");
    private boolean isLinux = osName.contains("nux") || osName.contains("nix");
    private boolean isMac = osName.contains("mac");

    // ==================== 业务组件 ====================
    private Process livegoProcess;
    private Process ffmpegProcess;
    private Process frpProcess;
    private Server jettyServer;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private boolean isRtmpRunning = false;
    private boolean isTranscoding = false;
    private boolean isFrpRunning = false;
    private String localIP = "127.0.0.1";
    private String ffmpegPath = "ffmpeg";
    private String frpConfigPath = "";
    private boolean hasOBSConnection = false;
    private PrintWriter logFileWriter;
    private String logDir = "./logs";
    private String currentStreamKey = "";

    // ==================== 配置 ====================
    private static class Config {
        int rtmpPort = 1935;
        int hlsPort = 7002;
        int flvPort = 7001;
        int apiPort = 8090;
        String streamName = "live";
        String subtitleText = "深水6";
        String fontPath = "./fonts/SourceHanSansCN-Bold.otf";
        int fontSize = 24;
        String fontColor = "#FFFFFF";
        boolean shadowEnabled = true;
        int httpPort = 8080;
        String hlsDir = "./hls";
        String livegoDir = "./libs/livego";
        String ffmpegDir = "./libs/FFmpeg/bin";
        String fontsDir = "./fonts";
    }
    private Config config = new Config();

    private String getExeSuffix() {
        return isWindows ? ".exe" : "";
    }

    private String getFfmpegName() {
        return isWindows ? "ffmpeg.exe" : "ffmpeg";
    }

    private String getLivegoName() {
        if (isWindows) return "livego_windows_amd64.exe";
        if (isLinux) return "livego";
        if (isMac) return "livego_macos";
        return "livego";
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        initLogFile();
        getLocalIP();
        checkLivego();
        checkFFmpeg();

        // 创建圆润透明窗口
        createUI(stage);
        initializeApp();

        stage.setTitle("Y Live");
        stage.setMinWidth(1100);
        stage.setMinHeight(750);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
    }

    // ==================== 日志初始化 ====================
    private void initLogFile() {
        try {
            Files.createDirectories(Paths.get(logDir));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            Path logPath = Paths.get(logDir, "ylive_" + timestamp + ".log");
            logFileWriter = new PrintWriter(new FileWriter(logPath.toFile(), true));
            logToFile("========================================");
            logToFile("  Y Live v1.0 启动");
            logToFile("  时间: " + new Date());
            logToFile("  平台: " + osName);
            logToFile("  ©2026 晏阳技术组 GPLv3");
            logToFile("========================================");
        } catch (IOException e) {
            System.err.println("创建日志文件失败: " + e.getMessage());
        }
    }

    private void logToFile(String msg) {
        if (logFileWriter != null) {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            logFileWriter.println("[" + ts + "] " + msg);
            logFileWriter.flush();
        }
    }

    // ==================== 复制功能 ====================
    private void copyToClipboard(String text, String name) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
        log("📋 已复制 " + name + ": " + text);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("复制成功");
        alert.setHeaderText(null);
        alert.setContentText("已复制 " + name + ":\n" + text);
        alert.showAndWait();
    }

    // ==================== 检查livego ====================
    private void checkLivego() {
        String livegoName = getLivegoName();
        String livegoExe = config.livegoDir + "/" + livegoName;

        if (Files.exists(Paths.get(livegoExe))) {
            log("✅ livego已就绪: " + livegoExe);
            if (!isWindows) {
                new File(livegoExe).setExecutable(true);
            }
        } else {
            log("⚠ livego未找到，请下载到: " + config.livegoDir);
            log("💡 下载地址: https://github.com/gwuhaolin/livego/releases");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("livego未找到");
                alert.setHeaderText("请下载livego");
                alert.setContentText("""
                    livego是RTMP服务器组件。

                    请从以下地址下载对应平台版本：
                    https://github.com/gwuhaolin/livego/releases

                    Windows: livego_windows_amd64.exe
                    Linux: livego
                    macOS: livego_macos

                    放到: """ + config.livegoDir);
                alert.showAndWait();
            });
        }
    }

    // ==================== 检查FFmpeg ====================
    private void checkFFmpeg() {
        String ffmpegExe = getFfmpegName();
        String builtinFfmpeg = config.ffmpegDir + "/" + ffmpegExe;

        if (Files.exists(Paths.get(builtinFfmpeg))) {
            ffmpegPath = builtinFfmpeg;
            log("✅ 找到内置FFmpeg: " + builtinFfmpeg);
            if (!isWindows) {
                new File(builtinFfmpeg).setExecutable(true);
            }
            return;
        }

        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                ffmpegPath = "ffmpeg";
                log("✅ 系统FFmpeg已安装");
                return;
            }
        } catch (Exception e) {}

        log("⚠ FFmpeg未找到，请安装FFmpeg");
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("FFmpeg未找到");
            alert.setHeaderText("请安装FFmpeg");
            alert.setContentText("""
                字幕烧录和转码需要FFmpeg。

                Windows: 下载 ffmpeg.exe 放到 libs/FFmpeg/bin/
                Linux: sudo apt install ffmpeg
                macOS: brew install ffmpeg

                或从以下地址下载：
                https://ffmpeg.org/download.html
                """);
            alert.showAndWait();
        });
    }

    // ==================== UI创建 ====================
    private void createUI(Stage stage) {
        // 设置透明圆润窗口
        stage.initStyle(StageStyle.TRANSPARENT);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        // 主容器 - 圆润背景
        VBox mainContainer = new VBox();
        mainContainer.setStyle(
                "-fx-background-color: #f0f2f5; " +
                        "-fx-background-radius: 20; " +
                        "-fx-border-radius: 20; " +
                        "-fx-border-color: rgba(200,200,200,0.3); " +
                        "-fx-border-width: 1; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 10);"
        );
        mainContainer.setPadding(new Insets(5));

        // ===== 自定义标题栏 =====
        HBox titleBar = createTitleBar(stage);
        mainContainer.getChildren().add(titleBar);

        // ===== 内容区域 =====
        VBox contentArea = new VBox();
        contentArea.setStyle("-fx-background-color: transparent; -fx-padding: 0 10 10 10;");
        contentArea.setSpacing(10);

        // 标题
        Label title = new Label("");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox titleBox = new HBox(title);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.setPadding(new Insets(5, 0, 10, 0));

        // 中间三栏
        HBox centerBox = new HBox(15);
        centerBox.setPadding(new Insets(5, 0, 5, 0));
        centerBox.getChildren().addAll(
                createRtmpPanel(),
                createSubtitlePanel(),
                createFrpPanel()
        );

        // 底部
        VBox bottomBox = new VBox(10);
        bottomBox.getChildren().addAll(
                createTranscodePanel(),
                createLogPanel()
        );

        VBox footerBox = new VBox(2);
        footerBox.setAlignment(Pos.CENTER);
        footerBox.setPadding(new Insets(5, 0, 5, 0));
        Label footerLabel = new Label("©2026 晏阳技术组  基于 GPLv3 协议开源");
        footerLabel.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
        footerBox.getChildren().add(footerLabel);

        contentArea.getChildren().addAll(titleBox, centerBox, bottomBox, footerBox);
        mainContainer.getChildren().add(contentArea);

        root.setCenter(mainContainer);

        Scene scene = new Scene(root, 1200, 800, Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());

        // 窗口拖动
        scene.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        scene.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });

        stage.setScene(scene);
    }

    // ===== 自定义标题栏 =====
    private HBox createTitleBar(Stage stage) {
        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(5, 10, 5, 10));
        titleBar.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-background-radius: 15 15 0 0;"
        );

        // 标题文字
        Label titleLabel = new Label("Y Live");
        titleLabel.setStyle(
                "-fx-font-size: 16px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-text-fill: #1a237e;"
        );
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        // 最小化按钮
        Button minBtn = createTitleButton("─", "#f1f2f6", "#dfe4ea");
        minBtn.setOnAction(e -> stage.setIconified(true));

        // 最大化按钮
        Button maxBtn = createTitleButton("☐", "#f1f2f6", "#dfe4ea");
        maxBtn.setOnAction(e -> {
            if (stage.isMaximized()) {
                stage.setMaximized(false);
            } else {
                stage.setMaximized(true);
            }
        });

        // 关闭按钮
        Button closeBtn = createTitleButton("✕", "#ff4757", "#ff6b81");
        closeBtn.setOnAction(e -> shutdown());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        titleBar.getChildren().addAll(titleLabel, spacer, minBtn, maxBtn, closeBtn);
        return titleBar;
    }

    private Button createTitleButton(String text, String bg, String hoverBg) {
        Button btn = new Button(text);
        btn.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-text-fill: #2c3e50; " +
                        "-fx-font-size: 14px; " +
                        "-fx-min-width: 30px; " +
                        "-fx-min-height: 30px; " +
                        "-fx-max-width: 30px; " +
                        "-fx-max-height: 30px; " +
                        "-fx-border-radius: 4; " +
                        "-fx-background-radius: 4; " +
                        "-fx-cursor: hand; " +
                        "-fx-padding: 0;"
        );
        btn.setOnMouseEntered(e -> {
            if (!btn.getText().equals("✕")) {
                btn.setStyle("-fx-background-color: " + hoverBg + "; -fx-text-fill: #2c3e50; -fx-font-size: 14px; -fx-min-width: 30px; -fx-min-height: 30px; -fx-max-width: 30px; -fx-max-height: 30px; -fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 0;");
            } else {
                btn.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-font-size: 14px; -fx-min-width: 30px; -fx-min-height: 30px; -fx-max-width: 30px; -fx-max-height: 30px; -fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 0;");
            }
        });
        btn.setOnMouseExited(e -> {
            if (!btn.getText().equals("✕")) {
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #2c3e50; -fx-font-size: 14px; -fx-min-width: 30px; -fx-min-height: 30px; -fx-max-width: 30px; -fx-max-height: 30px; -fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 0;");
            } else {
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #2c3e50; -fx-font-size: 14px; -fx-min-width: 30px; -fx-min-height: 30px; -fx-max-width: 30px; -fx-max-height: 30px; -fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 0;");
            }
        });
        return btn;
    }

    // ==================== 面板创建方法 ====================
    private VBox createRtmpPanel() {
        VBox panel = createPanel("📡 RTMP服务");

        HBox portBox = new HBox(10);
        portBox.setAlignment(Pos.CENTER_LEFT);
        rtmpPortField = new TextField("1935");
        streamNameField = new TextField("live");
        rtmpPortField.setPrefWidth(80);
        streamNameField.setPrefWidth(100);
        portBox.getChildren().addAll(
                new Label("RTMP端口:"), rtmpPortField,
                new Label("流名:"), streamNameField
        );

        HBox hlsPortBox = new HBox(10);
        hlsPortBox.setAlignment(Pos.CENTER_LEFT);
        hlsPortField = new TextField("7002");
        flvPortField = new TextField("7001");
        apiPortField = new TextField("8090");
        hlsPortField.setPrefWidth(60);
        flvPortField.setPrefWidth(60);
        apiPortField.setPrefWidth(60);
        hlsPortBox.getChildren().addAll(
                new Label("HLS端口:"), hlsPortField,
                new Label("FLV端口:"), flvPortField,
                new Label("API端口:"), apiPortField
        );

        HBox statusBox = new HBox(10);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        rtmpProgress = new ProgressIndicator();
        rtmpProgress.setPrefSize(20, 20);
        rtmpProgress.setVisible(false);
        rtmpStatusLabel = new Label("○ 离线");
        rtmpStatusLabel.setTextFill(Color.RED);
        connectionStatusLabel = new Label("等待OBS连接...");
        connectionStatusLabel.setStyle("-fx-text-fill: #888;");
        statusBox.getChildren().addAll(rtmpProgress, rtmpStatusLabel, connectionStatusLabel);

        HBox btnBox = new HBox(10);
        startRtmpBtn = createButton("▶ 启动RTMP服务", "#27ae60");
        stopRtmpBtn = createButton("⏹ 停止", "#e74c3c");
        stopRtmpBtn.setDisable(true);
        btnBox.getChildren().addAll(startRtmpBtn, stopRtmpBtn);

        HBox getKeyBox = new HBox(10);
        getKeyBox.setAlignment(Pos.CENTER_LEFT);
        getStreamKeyBtn = createButton("🔑 获取推流码", "#9b59b6");
        getStreamKeyBtn.setOnAction(e -> getStreamKey());
        getStreamKeyBtn.setDisable(true);
        getKeyBox.getChildren().addAll(getStreamKeyBtn, new Label("请先启动RTMP服务"));
        getKeyBox.setPadding(new Insets(5, 0, 5, 0));

        Label infoTitle = new Label("📋 推流信息");
        infoTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 14px;");

        HBox keyBox = new HBox(5);
        keyBox.setAlignment(Pos.CENTER_LEFT);
        keyBox.getChildren().addAll(new Label("推流码:"));
        streamKeyDisplayField = new TextField();
        streamKeyDisplayField.setEditable(false);
        streamKeyDisplayField.setPrefWidth(150);
        streamKeyDisplayField.setStyle("-fx-background-color: #ecf0f1; -fx-font-family: monospace;");
        copyStreamKeyBtn = createSmallButton("📋 复制");
        keyBox.getChildren().addAll(streamKeyDisplayField, copyStreamKeyBtn);
        copyStreamKeyBtn.setOnAction(e -> {
            if (!streamKeyDisplayField.getText().isEmpty()) {
                copyToClipboard(streamKeyDisplayField.getText(), "推流码");
            }
        });

        HBox pushBox = new HBox(5);
        pushBox.setAlignment(Pos.CENTER_LEFT);
        pushBox.getChildren().addAll(new Label("推流地址:"));
        pushUrlLabel = new Label("rtmp://127.0.0.1:1935/live");
        pushUrlLabel.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 4 8; -fx-font-family: monospace; -fx-background-radius: 4;");
        copyPushUrlBtn = createSmallButton("📋 复制");
        pushBox.getChildren().addAll(pushUrlLabel, copyPushUrlBtn);
        copyPushUrlBtn.setOnAction(e -> copyToClipboard(pushUrlLabel.getText(), "推流地址"));

        HBox hlsBox = new HBox(5);
        hlsBox.setAlignment(Pos.CENTER_LEFT);
        hlsBox.getChildren().addAll(new Label("HLS地址:"));
        hlsUrlLabel = new Label("http://127.0.0.1:7002/live/live.m3u8");
        hlsUrlLabel.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 4 8; -fx-font-family: monospace; -fx-background-radius: 4;");
        copyHlsUrlBtn = createSmallButton("📋 复制");
        hlsBox.getChildren().addAll(hlsUrlLabel, copyHlsUrlBtn);
        copyHlsUrlBtn.setOnAction(e -> copyToClipboard(hlsUrlLabel.getText(), "HLS地址"));

        HBox flvBox = new HBox(5);
        flvBox.setAlignment(Pos.CENTER_LEFT);
        flvBox.getChildren().addAll(new Label("FLV地址:"));
        flvUrlLabel = new Label("http://127.0.0.1:7001/live/live.flv");
        flvUrlLabel.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 4 8; -fx-font-family: monospace; -fx-background-radius: 4;");
        copyFlvUrlBtn = createSmallButton("📋 复制");
        flvBox.getChildren().addAll(flvUrlLabel, copyFlvUrlBtn);
        copyFlvUrlBtn.setOnAction(e -> copyToClipboard(flvUrlLabel.getText(), "FLV地址"));

        Label tipLabel = new Label("💡 点击「获取推流码」自动生成，复制到OBS即可推流");
        tipLabel.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 11px;");

        VBox infoBox = new VBox(5);
        infoBox.setPadding(new Insets(8, 0, 5, 0));
        infoBox.getChildren().addAll(infoTitle, keyBox, pushBox, hlsBox, flvBox, tipLabel);

        panel.getChildren().addAll(portBox, hlsPortBox, statusBox, btnBox, getKeyBox, infoBox);
        return panel;
    }

    private Button createSmallButton(String text) {
        Button btn = new Button(text);
        btn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8; -fx-cursor: hand; -fx-background-radius: 4;");
        return btn;
    }

    private VBox createSubtitlePanel() {
        VBox panel = createPanel("✏️ 字幕配置");

        HBox contentBox = new HBox(10);
        contentBox.setAlignment(Pos.CENTER_LEFT);
        subtitleTextField = new TextField("深水6");
        subtitleTextField.setPrefWidth(200);
        contentBox.getChildren().addAll(new Label("内容:"), subtitleTextField);

        HBox fontBox = new HBox(10);
        fontBox.setAlignment(Pos.CENTER_LEFT);
        fontPathField = new TextField();
        fontPathField.setEditable(false);
        fontPathField.setPrefWidth(150);
        fontPathField.setText("./fonts/SourceHanSansCN-Bold.otf");
        config.fontPath = "./fonts/SourceHanSansCN-Bold.otf";
        browseFontBtn = createButton("浏览...", "#3498db");
        fontBox.getChildren().addAll(new Label("字体:"), fontPathField, browseFontBtn);

        HBox styleBox = new HBox(10);
        styleBox.setAlignment(Pos.CENTER_LEFT);
        fontSizeSlider = new Slider(12, 72, 24);
        fontSizeSlider.setPrefWidth(100);
        fontSizeLabel = new Label("24px");
        fontColorPicker = new ColorPicker(Color.WHITE);
        shadowCheckBox = new CheckBox("阴影");
        shadowCheckBox.setSelected(true);
        styleBox.getChildren().addAll(
                new Label("字号:"), fontSizeSlider, fontSizeLabel,
                new Label("颜色:"), fontColorPicker, shadowCheckBox
        );

        previewSubtitleBtn = createButton("👁 预览字幕效果", "#3498db");

        panel.getChildren().addAll(contentBox, fontBox, styleBox, previewSubtitleBtn);
        return panel;
    }

    private VBox createFrpPanel() {
        VBox panel = createPanel("🔗 FRP穿透");

        HBox statusBox = new HBox(10);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        frpStatusLabel = new Label("⏹ 未连接");
        frpStatusLabel.setStyle("-fx-text-fill: #888;");
        statusBox.getChildren().addAll(new Label("状态:"), frpStatusLabel);

        HBox fileBox = new HBox(10);
        fileBox.setAlignment(Pos.CENTER_LEFT);
        frpConfigPathField = new TextField();
        frpConfigPathField.setEditable(false);
        frpConfigPathField.setPrefWidth(200);
        browseFrpBtn = createButton("📂 选择配置文件", "#3498db");
        fileBox.getChildren().addAll(new Label("配置文件:"), frpConfigPathField, browseFrpBtn);

        HBox btnBox = new HBox(10);
        startFrpBtn = createButton("▶ 启动穿透", "#27ae60");
        stopFrpBtn = createButton("⏹ 停止穿透", "#e74c3c");
        stopFrpBtn.setDisable(true);
        btnBox.getChildren().addAll(startFrpBtn, stopFrpBtn);

        Label tipLabel = new Label("💡 选择 frpc.toml 配置文件，点击启动穿透");
        tipLabel.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 12px;");

        panel.getChildren().addAll(statusBox, fileBox, btnBox, tipLabel);
        return panel;
    }

    private HBox createTranscodePanel() {
        HBox panel = new HBox(15);
        panel.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-border-radius: 8; " +
                "-fx-background-radius: 8; -fx-border-color: #dcdcdc;");
        panel.setAlignment(Pos.CENTER_LEFT);

        startTranscodeBtn = createButton("▶ 启动转码", "#2ecc71");
        stopTranscodeBtn = createButton("⏹ 停止转码", "#e74c3c");
        stopTranscodeBtn.setDisable(true);

        panel.getChildren().addAll(startTranscodeBtn, stopTranscodeBtn);
        return panel;
    }

    private VBox createLogPanel() {
        VBox panel = new VBox(5);
        panel.setStyle("-fx-background-color: #1e1e1e; -fx-padding: 10; -fx-border-radius: 8; " +
                "-fx-background-radius: 8; -fx-border-color: #333;");
        VBox.setVgrow(panel, Priority.ALWAYS);

        HBox logHeader = new HBox(10);
        logHeader.setAlignment(Pos.CENTER_LEFT);
        Label logLabel = new Label("📋 日志面板");
        logLabel.setStyle("-fx-text-fill: #a0a0a0; -fx-font-size: 14px; -fx-font-weight: bold;");
        Button clearLogBtn = new Button("清空");
        clearLogBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-size: 11px; -fx-background-radius: 4;");
        clearLogBtn.setOnAction(e -> logArea.clear());
        logHeader.getChildren().addAll(logLabel, clearLogBtn);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px; -fx-text-fill: #00ff00; " +
                "-fx-background-color: #1e1e1e; -fx-control-inner-background: #1e1e1e;");
        logArea.setPrefHeight(180);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        panel.getChildren().addAll(logHeader, logArea);
        return panel;
    }

    private VBox createPanel(String title) {
        VBox panel = new VBox(10);
        panel.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-border-radius: 10; " +
                "-fx-background-radius: 10; -fx-border-color: #dcdcdc; -fx-border-width: 1; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 3);");
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        panel.getChildren().add(titleLabel);
        return panel;
    }

    private Button createButton(String text, String color) {
        Button btn = new Button(text);
        btn.setStyle(
                "-fx-background-color: " + color + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand; " +
                        "-fx-padding: 8 16; " +
                        "-fx-background-radius: 6; " +
                        "-fx-border-radius: 6;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: " + color + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand; " +
                        "-fx-padding: 8 16; " +
                        "-fx-background-radius: 6; " +
                        "-fx-border-radius: 6; " +
                        "-fx-opacity: 0.85;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: " + color + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-weight: bold; " +
                        "-fx-cursor: hand; " +
                        "-fx-padding: 8 16; " +
                        "-fx-background-radius: 6; " +
                        "-fx-border-radius: 6;"
        ));
        return btn;
    }

    // ==================== 初始化 ====================
    private void initializeApp() {
        try {
            Files.createDirectories(Paths.get(config.hlsDir));
            Files.createDirectories(Paths.get(config.fontsDir));
            log("📁 创建目录: " + config.hlsDir);
        } catch (IOException e) {
            log("❌ 创建目录失败: " + e.getMessage());
        }

        loadCustomFont();
        checkSystemFonts();
        setupEvents();
        updateUrls();

        log("🚀 Y Live v1.0 启动成功");
        log("📡 本地IP: " + localIP);
        log("💻 平台: " + osName);
        log("💡 使用步骤:");
        log("   1. 点击 '启动RTMP服务' 启动服务器");
        log("   2. 点击 '获取推流码' 获取推流码");
        log("   3. 复制推流码到OBS");
        log("   4. OBS推流");
        log("   5. 点击 '启动转码' 开始字幕烧录");
    }

    // ==================== 加载自定义字体 ====================
    private void loadCustomFont() {
        try {
            String fontPath = config.fontsDir + "/SourceHanSansCN-Bold.otf";
            File fontFile = new File(fontPath);
            if (fontFile.exists()) {
                Font.loadFont(new FileInputStream(fontFile), 14);
                log("✅ 加载自定义字体: SourceHanSansCN-Bold.otf");
            } else {
                log("⚠️ 自定义字体未找到: " + fontPath);
                log("💡 请将 SourceHanSansCN-Bold.otf 放到: " + config.fontsDir);
            }
        } catch (Exception e) {
            log("⚠️ 加载字体失败: " + e.getMessage());
        }
    }

    private void getLocalIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (!ip.contains(":")) {
                            localIP = ip;
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            localIP = "127.0.0.1";
        }
    }

    // ==================== 检查系统字体 ====================
    private void checkSystemFonts() {
        String fontPath = config.fontsDir + "/SourceHanSansCN-Bold.otf";
        if (Files.exists(Paths.get(fontPath))) {
            config.fontPath = fontPath;
            fontPathField.setText(fontPath);
            log("✅ 找到字体: SourceHanSansCN-Bold.otf");
            return;
        }

        String[] paths;
        if (isWindows) {
            paths = new String[]{
                    "C:/Windows/Fonts/msyh.ttf",
                    "C:/Windows/Fonts/SimHei.ttf"
            };
        } else if (isMac) {
            paths = new String[]{
                    "/System/Library/Fonts/PingFang.ttc"
            };
        } else {
            paths = new String[]{
                    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
            };
        }

        for (String path : paths) {
            if (Files.exists(Paths.get(path))) {
                config.fontPath = path;
                fontPathField.setText(path);
                log("✅ 找到字体: " + Paths.get(path).getFileName());
                return;
            }
        }

        log("⚠️ 未找到字体文件，将使用系统默认字体");
        log("💡 请将 SourceHanSansCN-Bold.otf 放到: " + config.fontsDir);
    }

    private void setupEvents() {
        startRtmpBtn.setOnAction(e -> startRtmpServer());
        stopRtmpBtn.setOnAction(e -> stopRtmpServer());
        browseFontBtn.setOnAction(e -> browseFont());
        fontSizeSlider.valueProperty().addListener((obs, old, val) ->
                fontSizeLabel.setText(val.intValue() + "px"));
        previewSubtitleBtn.setOnAction(e -> previewSubtitle());
        startTranscodeBtn.setOnAction(e -> startTranscoding());
        stopTranscodeBtn.setOnAction(e -> stopTranscoding());

        browseFrpBtn.setOnAction(e -> browseFrpConfig());
        startFrpBtn.setOnAction(e -> startFrp());
        stopFrpBtn.setOnAction(e -> stopFrp());

        rtmpPortField.textProperty().addListener((obs, old, val) -> updateUrls());
        streamNameField.textProperty().addListener((obs, old, val) -> updateUrls());
        hlsPortField.textProperty().addListener((obs, old, val) -> updateUrls());
        flvPortField.textProperty().addListener((obs, old, val) -> updateUrls());
    }

    private void browseFont() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择字体文件");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("字体", "*.ttf", "*.otf", "*.ttc"));
        File file = fc.showOpenDialog(null);
        if (file != null) {
            fontPathField.setText(file.getAbsolutePath());
            config.fontPath = file.getAbsolutePath();
            log("选择字体: " + file.getName());
        }
    }

    private void previewSubtitle() {
        String text = subtitleTextField.getText().trim();
        if (text.isEmpty()) {
            showAlert("提示", "请输入字幕内容");
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("字幕预览");
        alert.setContentText(String.format("""
            字幕内容: %s
            字号: %dpx
            颜色: %s
            阴影: %s
            位置: 左下角
            """,
                text, (int)fontSizeSlider.getValue(),
                toHex(fontColorPicker.getValue()),
                shadowCheckBox.isSelected() ? "开启" : "关闭"
        ));
        alert.showAndWait();
    }

    private void updateUrls() {
        String port = rtmpPortField.getText().trim();
        String stream = streamNameField.getText().trim();
        String hlsPort = hlsPortField != null ? hlsPortField.getText().trim() : "7002";
        String flvPort = flvPortField != null ? flvPortField.getText().trim() : "7001";

        if (!port.isEmpty() && !stream.isEmpty()) {
            String url = "rtmp://" + localIP + ":" + port + "/" + stream;
            pushUrlLabel.setText(url);
        }

        if (!stream.isEmpty()) {
            hlsUrlLabel.setText("http://" + localIP + ":" + hlsPort + "/" + stream + "/" + stream + ".m3u8");
            if (flvUrlLabel != null) {
                flvUrlLabel.setText("http://" + localIP + ":" + flvPort + "/" + stream + "/" + stream + ".flv");
            }
        }
    }

    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int)(color.getRed() * 255),
                (int)(color.getGreen() * 255),
                (int)(color.getBlue() * 255));
    }

    // ==================== 端口监控检测OBS连接 ====================
    private void monitorRTMPConnection() {
        executor.submit(() -> {
            while (isRtmpRunning) {
                try {
                    boolean hasConnection = false;
                    int port = Integer.parseInt(rtmpPortField.getText().trim());

                    Process process = Runtime.getRuntime().exec("netstat -ano | findstr " + port);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("ESTABLISHED") && line.contains(":" + port)) {
                            hasConnection = true;
                            break;
                        }
                    }
                    reader.close();

                    final boolean connected = hasConnection;
                    Platform.runLater(() -> {
                        if (connected && !hasOBSConnection) {
                            hasOBSConnection = true;
                            connectionStatusLabel.setText("✅ OBS已连接!");
                            connectionStatusLabel.setStyle("-fx-text-fill: #2ecc71;");
                            rtmpProgress.setVisible(false);
                            log("🎥 OBS推流已连接！");
                        } else if (!connected && hasOBSConnection) {
                            hasOBSConnection = false;
                            connectionStatusLabel.setText("⏸ OBS已断开");
                            connectionStatusLabel.setStyle("-fx-text-fill: #e74c3c;");
                            rtmpProgress.setVisible(true);
                            log("⏸ OBS推流已断开");
                        }
                    });

                    Thread.sleep(2000);
                } catch (Exception e) {
                    // 忽略异常
                }
            }
        });
    }

    // ==================== 获取推流码 ====================
    private void getStreamKey() {
        if (!isRtmpRunning) {
            log("⚠️ 请先启动RTMP服务");
            showAlert("提示", "请先启动RTMP服务再获取推流码");
            return;
        }

        if (getStreamKeyBtn.getText().contains("获取中")) {
            return;
        }

        int rtmpPort = Integer.parseInt(rtmpPortField.getText().trim());
        int apiPort = Integer.parseInt(apiPortField.getText().trim());
        String stream = streamNameField.getText().trim();

        log("🔑 正在获取推流码...");
        getStreamKeyBtn.setText("⏳ 获取中...");
        getStreamKeyBtn.setDisable(true);

        executor.submit(() -> {
            try {
                log("🔄 正在初始化推流...");
                ProcessBuilder ffpb = new ProcessBuilder(
                        ffmpegPath,
                        "-re",
                        "-f", "lavfi",
                        "-i", "testsrc=size=1280x720:rate=1:duration=1",
                        "-f", "flv",
                        "-v", "quiet",
                        "rtmp://127.0.0.1:" + rtmpPort + "/" + stream
                );
                ffpb.directory(new File("."));
                ffpb.redirectErrorStream(true);
                Process ffp = ffpb.start();
                ffp.waitFor(3, TimeUnit.SECONDS);
                ffp.destroyForcibly();

                log("📡 正在获取推流密钥...");
                String realKey = getKeyFromAPI(apiPort, stream);

                if (realKey == null || realKey.isEmpty()) {
                    log("❌ API获取key失败");
                    Platform.runLater(() -> {
                        showAlert("获取失败", "无法获取推流码，请检查livego是否正常运行");
                        getStreamKeyBtn.setText("🔑 获取推流码");
                        getStreamKeyBtn.setDisable(false);
                    });
                    return;
                }

                currentStreamKey = realKey;

                final String finalKey = realKey;
                final String rtmpUrl = "rtmp://" + localIP + ":" + rtmpPort + "/" + stream;
                Platform.runLater(() -> {
                    streamKeyDisplayField.setText(finalKey);
                    log("✅ 推流码获取成功!");
                    log("📡 推流地址: " + rtmpUrl);
                    log("🔑 推流码: " + finalKey);
                    log("📺 HLS地址: http://" + localIP + ":" + hlsPortField.getText().trim() + "/" + stream + "/" + stream + ".m3u8");
                    log("📺 FLV地址: http://" + localIP + ":" + flvPortField.getText().trim() + "/" + stream + "/" + stream + ".flv");
                    getStreamKeyBtn.setText("🔑 获取推流码");
                    getStreamKeyBtn.setDisable(false);
                });

                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("推流码获取成功");
                    alert.setHeaderText(null);
                    alert.setContentText(
                            "推流码: " + finalKey + "\n\n" +
                                    "推流地址: " + rtmpUrl + "\n\n" +
                                    "HLS地址: http://" + localIP + ":" + hlsPortField.getText().trim() + "/" + stream + "/" + stream + ".m3u8\n\n" +
                                    "FLV地址: http://" + localIP + ":" + flvPortField.getText().trim() + "/" + stream + "/" + stream + ".flv"
                    );
                    alert.showAndWait();
                });

            } catch (Exception e) {
                log("❌ 获取推流码失败: " + e.getMessage());
                Platform.runLater(() -> {
                    getStreamKeyBtn.setText("🔑 获取推流码");
                    getStreamKeyBtn.setDisable(false);
                });
            }
        });
    }

    // ==================== 从 API 获取 key ====================
    private String getKeyFromAPI(int apiPort, String stream) {
        try {
            String apiUrl = "http://127.0.0.1:" + apiPort + "/control/get?room=" + stream;
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String response = reader.readLine();
                reader.close();

                log("📡 API响应: " + response);

                if (response != null && response.contains("\"data\"")) {
                    String search = "\"data\"";
                    int dataIndex = response.indexOf(search);
                    if (dataIndex == -1) return null;

                    int start = response.indexOf("\"", dataIndex + 7) + 1;
                    int end = response.indexOf("\"", start);
                    if (end > start) {
                        return response.substring(start, end);
                    }

                    int keyIndex = response.indexOf("\"key\"", dataIndex);
                    if (keyIndex != -1) {
                        start = response.indexOf("\"", keyIndex + 7) + 1;
                        end = response.indexOf("\"", start);
                        if (end > start) {
                            return response.substring(start, end);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("⚠️ API请求失败: " + e.getMessage());
        }
        return null;
    }

    // ==================== RTMP服务器 (livego) ====================
    private void startRtmpServer() {
        if (isRtmpRunning) {
            log("RTMP服务已在运行");
            return;
        }

        try {
            String livegoName = getLivegoName();
            String killCmd = isWindows ? "taskkill /F /IM " + livegoName : "pkill -f " + livegoName;
            Process p = Runtime.getRuntime().exec(killCmd);
            p.waitFor(2, TimeUnit.SECONDS);
            log("🧹 已清理残留的livego进程");
        } catch (Exception e) {}

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            int rtmpPort = Integer.parseInt(rtmpPortField.getText().trim());
            int hlsPort = Integer.parseInt(hlsPortField.getText().trim());
            int flvPort = Integer.parseInt(flvPortField.getText().trim());
            int apiPort = Integer.parseInt(apiPortField.getText().trim());
            String stream = streamNameField.getText().trim();

            try (ServerSocket ss = new ServerSocket(rtmpPort)) {
            } catch (IOException e) {
                log("❌ RTMP端口 " + rtmpPort + " 已被占用");
                showAlert("端口冲突", "RTMP端口 " + rtmpPort + " 已被占用，请更换端口");
                return;
            }

            String livegoName = getLivegoName();
            String livegoExe = config.livegoDir + "/" + livegoName;
            if (!Files.exists(Paths.get(livegoExe))) {
                log("❌ livego未找到");
                showAlert("livego未找到", "请下载livego到: " + config.livegoDir);
                return;
            }

            if (!isWindows) {
                new File(livegoExe).setExecutable(true);
            }

            log("🎬 启动RTMP服务器 (livego)");
            log("📡 RTMP端口: " + rtmpPort);
            log("📺 HLS端口: " + hlsPort);
            log("📺 HTTP-FLV端口: " + flvPort);

            ProcessBuilder pb = new ProcessBuilder(
                    livegoExe,
                    "--rtmp_addr", ":" + rtmpPort,
                    "--hls_addr", ":" + hlsPort,
                    "--httpflv_addr", ":" + flvPort,
                    "--api_addr", ":" + apiPort,
                    "--level", "info"
            );
            pb.directory(new File(config.livegoDir));
            pb.redirectErrorStream(true);

            livegoProcess = pb.start();
            Thread.sleep(3000);

            isRtmpRunning = true;
            rtmpStatusLabel.setText("● 在线");
            rtmpStatusLabel.setTextFill(Color.GREEN);
            connectionStatusLabel.setText("等待OBS连接...");
            connectionStatusLabel.setStyle("-fx-text-fill: #f39c12;");
            rtmpProgress.setVisible(true);
            startRtmpBtn.setDisable(true);
            stopRtmpBtn.setDisable(false);
            getStreamKeyBtn.setDisable(false);

            final String fixedKey = currentStreamKey.isEmpty() ? "请点击获取推流码" : currentStreamKey;
            Platform.runLater(() -> {
                if (!currentStreamKey.isEmpty()) {
                    streamKeyDisplayField.setText(currentStreamKey);
                }
                String rtmpUrl = "rtmp://" + localIP + ":" + rtmpPort + "/" + stream;
                log("✅ RTMP服务器已启动!");
                log("📡 推流地址: " + rtmpUrl);
                if (!currentStreamKey.isEmpty()) {
                    log("🔑 推流码: " + currentStreamKey);
                }
                log("📺 HLS地址: http://" + localIP + ":" + hlsPort + "/" + stream + "/" + stream + ".m3u8");
                log("📺 HTTP-FLV地址: http://" + localIP + ":" + flvPort + "/" + stream + "/" + stream + ".flv");
                log("💡 点击「获取推流码」获取推流码");
            });

            executor.submit(() -> monitorLivegoProcess(livegoProcess));
            monitorRTMPConnection();

        } catch (Exception e) {
            log("❌ 启动RTMP失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void monitorLivegoProcess(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String logLine = line;
                Platform.runLater(() -> {
                    log("[livego] " + logLine);
                    if (logLine.contains("publish") || logLine.contains("Publish")) {
                        hasOBSConnection = true;
                        connectionStatusLabel.setText("✅ OBS已连接!");
                        connectionStatusLabel.setStyle("-fx-text-fill: #2ecc71;");
                        rtmpProgress.setVisible(false);
                        log("🎥 OBS推流已连接！");
                    }
                    if (logLine.contains("unpublish") || logLine.contains("Unpublish") || logLine.contains("delete")) {
                        hasOBSConnection = false;
                        connectionStatusLabel.setText("⏸ OBS已断开");
                        connectionStatusLabel.setStyle("-fx-text-fill: #e74c3c;");
                        rtmpProgress.setVisible(true);
                        log("⏸ OBS推流已断开");
                    }
                });
            }
        } catch (Exception e) {
            Platform.runLater(() -> log("[livego] 监控错误: " + e.getMessage()));
        }
    }

    private void stopRtmpServer() {
        if (!isRtmpRunning) return;

        log("⏹ 停止RTMP服务器...");

        if (livegoProcess != null) {
            livegoProcess.destroyForcibly();
            try {
                livegoProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            livegoProcess = null;
        }

        try {
            String livegoName = getLivegoName();
            String killCmd = isWindows ? "taskkill /F /IM " + livegoName : "pkill -f " + livegoName;
            Process p = Runtime.getRuntime().exec(killCmd);
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {}

        if (isTranscoding) stopTranscoding();

        isRtmpRunning = false;
        hasOBSConnection = false;
        getStreamKeyBtn.setDisable(true);
        Platform.runLater(() -> {
            streamKeyDisplayField.setText(currentStreamKey);
        });
        rtmpStatusLabel.setText("○ 离线");
        rtmpStatusLabel.setTextFill(Color.RED);
        connectionStatusLabel.setText("已停止");
        connectionStatusLabel.setStyle("-fx-text-fill: #888;");
        rtmpProgress.setVisible(false);
        startRtmpBtn.setDisable(false);
        stopRtmpBtn.setDisable(true);
        log("⏹ RTMP服务器已停止");
    }

    // ==================== 转码功能 ====================
    private void startTranscoding() {
        if (isTranscoding) {
            log("转码已在运行");
            return;
        }

        if (!isRtmpRunning) {
            log("⚠ 请先启动RTMP服务");
            showAlert("提示", "请先启动RTMP服务");
            return;
        }

        try {
            config.rtmpPort = Integer.parseInt(rtmpPortField.getText().trim());
            config.streamName = streamNameField.getText().trim();
            config.subtitleText = subtitleTextField.getText().trim();
            config.fontSize = (int)fontSizeSlider.getValue();
            config.fontColor = toHex(fontColorPicker.getValue());
            config.shadowEnabled = shadowCheckBox.isSelected();

            startHttpServer();
            Thread.sleep(2000);

            List<String> cmd = buildFFmpegCommand();
            log("🎬 启动转码...");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File("."));
            pb.redirectErrorStream(true);

            ffmpegProcess = pb.start();
            executor.submit(() -> monitorFfmpegProcess(ffmpegProcess));

            isTranscoding = true;
            startTranscodeBtn.setDisable(true);
            stopTranscodeBtn.setDisable(false);

            String hlsUrl = "http://" + localIP + ":" + config.httpPort + "/" + config.streamName + ".m3u8";
            log("✅ 转码已启动");
            log("📺 转码输出: " + hlsUrl);

        } catch (Exception e) {
            log("❌ 启动转码失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== 构建 FFmpeg 命令 ====================
    private List<String> buildFFmpegCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-re");
        cmd.add("-i");
        cmd.add("http://127.0.0.1:7001/" + config.streamName + "/" + config.streamName + ".flv");

        String fontPath = config.fontPath;
        if (fontPath.isEmpty()) {
            fontPath = config.fontsDir + "/SourceHanSansCN-Bold.otf";
            if (!Files.exists(Paths.get(fontPath))) {
                if (isWindows) {
                    fontPath = "C:/Windows/Fonts/SimHei.ttf";
                } else if (isMac) {
                    fontPath = "/System/Library/Fonts/PingFang.ttc";
                } else {
                    fontPath = "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc";
                }
            }
        }
        fontPath = fontPath.replace("\\", "/");

        try {
            // 使用相对路径而不是绝对路径
            Path subtitleFile = Paths.get("./subtitle.txt");
            Files.writeString(subtitleFile, config.subtitleText, StandardCharsets.UTF_8);

            // 改用相对路径，不要用绝对路径
            String filter = String.format(
                    "drawtext=textfile='subtitle.txt':fontfile='%s':x=10:y=H-th-30:fontcolor=%s:fontsize=%d%s",
                    fontPath, config.fontColor, config.fontSize,
                    config.shadowEnabled ? ":shadowx=2:shadowy=2" : ""
            );
            cmd.add("-vf");
            cmd.add(filter);

            log("📝 使用字幕文件: subtitle.txt");
        } catch (IOException e) {
            String cleanText = config.subtitleText
                    .replace("©", "(c)")
                    .replace("\"", "\\\"")
                    .replace(":", "\\:")
                    .replace("'", "\\'");
            String filter = String.format(
                    "drawtext=text='%s':fontfile='%s':x=10:y=H-th-30:fontcolor=%s:fontsize=%d%s",
                    cleanText, fontPath, config.fontColor, config.fontSize,
                    config.shadowEnabled ? ":shadowx=2:shadowy=2" : ""
            );
            cmd.add("-vf");
            cmd.add(filter);
            log("⚠️ 使用降级字幕方案: " + cleanText);
        }

        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-f");
        cmd.add("hls");
        cmd.add("-hls_time");
        cmd.add("4");
        cmd.add("-hls_list_size");
        cmd.add("6");
        cmd.add("-hls_flags");
        cmd.add("delete_segments");
        cmd.add("-hls_segment_filename");
        cmd.add(config.hlsDir + "/segment_%03d.ts");
        cmd.add(config.hlsDir + "/" + config.streamName + ".m3u8");

        return cmd;
    }

    private void stopTranscoding() {
        if (!isTranscoding) return;

        log("⏹ 停止转码...");

        if (ffmpegProcess != null) {
            ffmpegProcess.destroyForcibly();
            try {
                ffmpegProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ffmpegProcess = null;
        }

        isTranscoding = false;
        startTranscodeBtn.setDisable(false);
        stopTranscodeBtn.setDisable(true);
        stopHttpServer();
        log("⏹ 转码已停止");
    }

    private void monitorFfmpegProcess(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String logLine = line;
                Platform.runLater(() -> {
                    if (logLine.contains("error") || logLine.contains("Error")) {
                        log("[FFmpeg] ❌ " + logLine);
                    } else if (logLine.contains("frame=") && logLine.contains("fps=")) {
                        log("[FFmpeg] " + logLine);
                    }
                });
            }
        } catch (Exception e) {
            Platform.runLater(() -> log("[FFmpeg] 监控错误: " + e.getMessage()));
        }
    }

    // ==================== HTTP服务器 ====================
    private void startHttpServer() {
        try {
            if (jettyServer != null && jettyServer.isRunning()) {
                return;
            }

            String hlsPath = config.hlsDir;
            File hlsDir = new File(hlsPath);
            if (!hlsDir.exists()) {
                hlsDir.mkdirs();
            }
            log("📁 托管HLS目录: " + hlsPath);

            jettyServer = new Server(config.httpPort);
            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath("/");
            context.setResourceBase(hlsDir.getAbsolutePath());
            context.addServlet(new ServletHolder("default", DefaultServlet.class), "/*");

            jettyServer.setHandler(context);
            jettyServer.start();
            log("✅ HTTP服务器已启动，端口: " + config.httpPort);

        } catch (Exception e) {
            log("❌ HTTP服务器启动失败: " + e.getMessage());
        }
    }

    private void stopHttpServer() {
        try {
            if (jettyServer != null && jettyServer.isRunning()) {
                jettyServer.stop();
                jettyServer = null;
                log("HTTP服务器已停止");
            }
        } catch (Exception e) {
            log("停止HTTP服务器失败: " + e.getMessage());
        }
    }

    // ==================== FRP功能 ====================
    private void browseFrpConfig() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择FRP配置文件");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("TOML文件", "*.toml"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("所有文件", "*.*"));

        File file = fc.showOpenDialog(null);
        if (file != null) {
            frpConfigPathField.setText(file.getAbsolutePath());
            frpConfigPath = file.getAbsolutePath();
            log("📂 选择FRP配置: " + file.getName());
        }
    }

    private void startFrp() {
        if (isFrpRunning) {
            log("FRP穿透已在运行");
            return;
        }

        if (frpConfigPath.isEmpty() || !Files.exists(Paths.get(frpConfigPath))) {
            log("❌ 请先选择有效的FRP配置文件");
            showAlert("提示", "请先选择frpc.toml配置文件");
            return;
        }

        try {
            log("🔗 启动FRP穿透...");
            log("📄 配置文件: " + frpConfigPath);

            String frpcExe = isWindows ? "frpc.exe" : "frpc";
            String[] possiblePaths = {
                    "./libs/frpc/" + frpcExe,
                    "./" + frpcExe,
                    frpcExe
            };

            String frpcPath = null;
            for (String path : possiblePaths) {
                if (Files.exists(Paths.get(path))) {
                    frpcPath = path;
                    break;
                }
            }

            if (frpcPath == null) {
                log("❌ 未找到frpc，请下载FRP客户端");
                showAlert("FRP未找到", "请下载frpc并放到libs目录或添加到PATH");
                return;
            }

            if (!isWindows) {
                new File(frpcPath).setExecutable(true);
            }

            ProcessBuilder pb = new ProcessBuilder(frpcPath, "-c", frpConfigPath);
            pb.redirectErrorStream(true);

            frpProcess = pb.start();
            executor.submit(() -> monitorFrpProcess(frpProcess));

            isFrpRunning = true;
            frpStatusLabel.setText("● 已连接");
            frpStatusLabel.setTextFill(Color.GREEN);
            startFrpBtn.setDisable(true);
            stopFrpBtn.setDisable(false);

            log("✅ FRP穿透已启动");

        } catch (Exception e) {
            log("❌ 启动FRP失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void monitorFrpProcess(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String logLine = line;
                Platform.runLater(() -> {
                    if (logLine.contains("error") || logLine.contains("Error")) {
                        log("[FRP] ❌ " + logLine);
                    } else if (logLine.contains("success") || logLine.contains("start")) {
                        log("[FRP] ✅ " + logLine);
                    } else {
                        log("[FRP] " + logLine);
                    }
                });
            }
        } catch (Exception e) {
            Platform.runLater(() -> log("[FRP] 监控错误: " + e.getMessage()));
        }
    }

    private void stopFrp() {
        if (!isFrpRunning) return;

        log("⏹ 停止FRP穿透...");

        if (frpProcess != null) {
            frpProcess.destroyForcibly();
            try {
                frpProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            frpProcess = null;
        }

        isFrpRunning = false;
        frpStatusLabel.setText("⏹ 已断开");
        frpStatusLabel.setTextFill(Color.RED);
        startFrpBtn.setDisable(false);
        stopFrpBtn.setDisable(true);
        log("⏹ FRP穿透已停止");
    }

    // ==================== 工具方法 ====================
    private void log(String msg) {
        Platform.runLater(() -> {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String fullMsg = "[" + ts + "] " + msg;
            logArea.appendText(fullMsg + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
            logToFile(msg);
        });
    }

    private void showAlert(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    private void shutdown() {
        log("🛑 正在关闭Y Live...");
        stopRtmpServer();
        stopTranscoding();
        stopFrp();
        stopHttpServer();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        if (logFileWriter != null) {
            logFileWriter.close();
        }
        log("👋 Y Live已关闭");
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}