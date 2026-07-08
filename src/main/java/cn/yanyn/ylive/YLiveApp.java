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
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
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
        String subtitleText = "玩家ID";
        String fontPath = "";
        int fontSize = 24;
        String fontColor = "#FFFFFF";
        boolean shadowEnabled = true;
        int httpPort = 8080;
        String hlsDir = "./hls";
        String livegoDir = "./libs/livego";
    }
    private Config config = new Config();

    @Override
    public void start(Stage primaryStage) {
        initLogFile();
        getLocalIP();
        checkLivego();
        createUI(primaryStage);
        initializeApp();
        primaryStage.setTitle("Y Live");
        primaryStage.setMinWidth(1100);
        primaryStage.setMinHeight(750);
        primaryStage.setOnCloseRequest(e -> shutdown());
        primaryStage.show();
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
        String livegoExe = config.livegoDir + "/livego_windows_amd64.exe";
        if (Files.exists(Paths.get(livegoExe))) {
            log("✅ livego已就绪");
        } else {
            log("⚠ livego未找到，请下载到: " + config.livegoDir);
            log("💡 下载地址: https://github.com/gwuhaolin/livego/releases");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("livego未找到");
                alert.setHeaderText("请下载livego");
                alert.setContentText("""
                    livego是RTMP服务器组件。

                    请从以下地址下载：
                    https://github.com/gwuhaolin/livego/releases

                    下载 livego_windows_amd64.exe 放到:
                    """ + config.livegoDir);
                alert.showAndWait();
            });
        }
    }

    // ==================== UI创建 ====================
    private void createUI(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f0f2f5; -fx-padding: 15;");

        Label title = new Label("Y Live v1.0.1");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");
        BorderPane.setAlignment(title, Pos.CENTER);
        root.setTop(title);

        HBox centerBox = new HBox(15);
        centerBox.setPadding(new Insets(15, 0, 15, 0));
        centerBox.getChildren().addAll(
                createRtmpPanel(),
                createSubtitlePanel(),
                createFrpPanel()
        );
        root.setCenter(centerBox);

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

        VBox mainBottomBox = new VBox(2);
        mainBottomBox.getChildren().addAll(bottomBox, footerBox);
        root.setBottom(mainBottomBox);

        Scene scene = new Scene(root, 1200, 800);
        stage.setScene(scene);
    }

    private VBox createRtmpPanel() {
        VBox panel = createPanel("📡 RTMP服务");

        // 端口和流名
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

        // HLS端口和HTTP-FLV端口
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

        // 状态
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

        // 按钮
        HBox btnBox = new HBox(10);
        startRtmpBtn = createButton("▶ 启动RTMP服务", "#27ae60");
        stopRtmpBtn = createButton("⏹ 停止", "#e74c3c");
        stopRtmpBtn.setDisable(true);
        btnBox.getChildren().addAll(startRtmpBtn, stopRtmpBtn);

        // 获取推流码按钮
        HBox getKeyBox = new HBox(10);
        getKeyBox.setAlignment(Pos.CENTER_LEFT);
        getStreamKeyBtn = createButton("🔑 获取推流码", "#9b59b6");
        getStreamKeyBtn.setOnAction(e -> getStreamKey());
        getStreamKeyBtn.setDisable(true);  // 默认禁用
        getKeyBox.getChildren().addAll(getStreamKeyBtn, new Label("请先启动RTMP服务"));
        getKeyBox.setPadding(new Insets(5, 0, 5, 0));

        // ========== 推流信息显示区域 ==========
        Label infoTitle = new Label("📋 推流信息");
        infoTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 14px;");

        // 推流码
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

        // 推流地址
        HBox pushBox = new HBox(5);
        pushBox.setAlignment(Pos.CENTER_LEFT);
        pushBox.getChildren().addAll(new Label("推流地址:"));
        pushUrlLabel = new Label("rtmp://127.0.0.1:1935/live");
        pushUrlLabel.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 4 8; -fx-font-family: monospace; -fx-background-radius: 4;");
        copyPushUrlBtn = createSmallButton("📋 复制");
        pushBox.getChildren().addAll(pushUrlLabel, copyPushUrlBtn);
        copyPushUrlBtn.setOnAction(e -> copyToClipboard(pushUrlLabel.getText(), "推流地址"));

        // HLS地址
        HBox hlsBox = new HBox(5);
        hlsBox.setAlignment(Pos.CENTER_LEFT);
        hlsBox.getChildren().addAll(new Label("HLS地址:"));
        hlsUrlLabel = new Label("http://127.0.0.1:7002/live/live.m3u8");
        hlsUrlLabel.setStyle("-fx-background-color: #ecf0f1; -fx-padding: 4 8; -fx-font-family: monospace; -fx-background-radius: 4;");
        copyHlsUrlBtn = createSmallButton("📋 复制");
        hlsBox.getChildren().addAll(hlsUrlLabel, copyHlsUrlBtn);
        copyHlsUrlBtn.setOnAction(e -> copyToClipboard(hlsUrlLabel.getText(), "HLS地址"));

        // FLV地址
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
        btn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8; -fx-cursor: hand;");
        return btn;
    }

    private VBox createSubtitlePanel() {
        VBox panel = createPanel("✏️ 字幕配置");

        HBox contentBox = new HBox(10);
        contentBox.setAlignment(Pos.CENTER_LEFT);
        subtitleTextField = new TextField("玩家ID");
        subtitleTextField.setPrefWidth(200);
        contentBox.getChildren().addAll(new Label("内容:"), subtitleTextField);

        HBox fontBox = new HBox(10);
        fontBox.setAlignment(Pos.CENTER_LEFT);
        fontPathField = new TextField();
        fontPathField.setEditable(false);
        fontPathField.setPrefWidth(150);
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
        clearLogBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-size: 11px;");
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
        panel.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-border-radius: 8; " +
                "-fx-background-radius: 8; -fx-border-color: #dcdcdc;");
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        panel.getChildren().add(titleLabel);
        return panel;
    }

    private Button createButton(String text, String color) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 8 16;");
        return btn;
    }

    // ==================== 初始化 ====================
    private void initializeApp() {
        try {
            Files.createDirectories(Paths.get(config.hlsDir));
            log("📁 创建目录: " + config.hlsDir);
        } catch (IOException e) {
            log("❌ 创建目录失败: " + e.getMessage());
        }

        checkFFmpeg();
        checkLivego();
        checkSystemFonts();
        setupEvents();
        updateUrls();

        log("🚀 Y Live v1.0.1 启动成功");
        log("📡 本地IP: " + localIP);
        log("💡 使用步骤:");
        log("   1. 点击 '启动RTMP服务' 启动服务器");
        log("   2. 点击 '获取推流码' 获取推流码");
        log("   3. 复制推流码到OBS");
        log("   4. OBS推流");
        log("   5. 点击 '启动转码' 开始字幕烧录");
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

    private void checkFFmpeg() {
        String builtinFfmpeg = "./libs/FFmpeg/bin/ffmpeg.exe";
        if (Files.exists(Paths.get(builtinFfmpeg))) {
            ffmpegPath = builtinFfmpeg;
            log("✅ 找到内置FFmpeg: " + builtinFfmpeg);
            return;
        }

        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                log("✅ FFmpeg已安装");
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

                请从以下地址下载安装：
                https://ffmpeg.org/download.html

                安装后将ffmpeg添加到系统PATH中。
                """);
            alert.showAndWait();
        });
    }

    private void checkSystemFonts() {
        String[] paths = {
                "C:/Windows/Fonts/msyh.ttf",
                "C:/Windows/Fonts/SimHei.ttf",
                "C:/Windows/Fonts/SourceHanSansSC-Regular.otf",
                "/System/Library/Fonts/PingFang.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
        };
        for (String path : paths) {
            if (Files.exists(Paths.get(path))) {
                config.fontPath = path;
                fontPathField.setText(path);
                log("✅ 找到字体: " + Paths.get(path).getFileName());
                return;
            }
        }
        log("ℹ️ 未找到中文字体，将使用系统默认字体");
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
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("字体", "*.ttf", "*.otf"));
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

        // 防止重复点击
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
                // 1. FFmpeg 推空流（不带推流码）
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

                // 2. 通过 API 获取真实的 channel key
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

                // 3. 显示推流码
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

                // 弹窗显示
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

                // 直接提取 data 后面的字符串
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

        // 清理残留进程
        try {
            Process p = Runtime.getRuntime().exec("taskkill /F /IM livego_windows_amd64.exe");
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

            String livegoExe = config.livegoDir + "/livego_windows_amd64.exe";
            if (!Files.exists(Paths.get(livegoExe))) {
                log("❌ livego未找到");
                showAlert("livego未找到", "请下载livego到: " + config.livegoDir);
                return;
            }

            log("🎬 启动RTMP服务器 (livego)");
            log("📡 RTMP端口: " + rtmpPort);
            log("📺 HLS端口: " + hlsPort);
            log("📺 HTTP-FLV端口: " + flvPort);

            // 用命令行启动 livego
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

            // 等待 livego 启动
            Thread.sleep(3000);

            isRtmpRunning = true;
            rtmpStatusLabel.setText("● 在线");
            rtmpStatusLabel.setTextFill(Color.GREEN);
            connectionStatusLabel.setText("等待OBS连接...");
            connectionStatusLabel.setStyle("-fx-text-fill: #f39c12;");
            rtmpProgress.setVisible(true);
            startRtmpBtn.setDisable(true);
            stopRtmpBtn.setDisable(false);
            getStreamKeyBtn.setDisable(false);  // 启用获取推流码按钮

            // 如果有推流码则显示
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

        // 强制杀死残留进程
        try {
            Process p = Runtime.getRuntime().exec("taskkill /F /IM livego_windows_amd64.exe");
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {}

        if (isTranscoding) stopTranscoding();

        isRtmpRunning = false;
        hasOBSConnection = false;
        getStreamKeyBtn.setDisable(true);  // 禁用获取推流码按钮
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

    private List<String> buildFFmpegCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        // 用 -re 实时速度读取 FLV
        cmd.add("-re");
        cmd.add("-i");
        cmd.add("http://127.0.0.1:7001/" + config.streamName + "/" + config.streamName + ".flv");

        // 字幕滤镜
        String fontPath = "./fonts/SourceHanSansCN-Bold.otf";
        if (!Files.exists(Paths.get(fontPath))) {
            fontPath = "C:/Windows/Fonts/SimHei.ttf";
        }
        fontPath = fontPath.replace("\\", "/");

        // 使用 textfile 方式加载字幕，避免特殊字符问题
        try {
            Path subtitleFile = Paths.get("./subtitle.txt");
            Files.writeString(subtitleFile, config.subtitleText, StandardCharsets.UTF_8);

            String filter = String.format(
                    "drawtext=textfile='%s':fontfile='%s':x=10:y=H-th-30:fontcolor=%s:fontsize=%d%s",
                    subtitleFile.toAbsolutePath().toString().replace("\\", "/"),
                    fontPath,
                    config.fontColor,
                    config.fontSize,
                    config.shadowEnabled ? ":shadowx=2:shadowy=2" : ""
            );
            cmd.add("-vf");
            cmd.add(filter);
        } catch (IOException e) {
            // 降级方案：直接使用文本，替换特殊字符
            String cleanText = config.subtitleText
                    .replace("©", "(c)")
                    .replace("\"", "\\\"")
                    .replace(":", "\\:");
            String filter = String.format(
                    "drawtext=text='%s':fontfile='%s':x=10:y=H-th-30:fontcolor=%s:fontsize=%d%s",
                    cleanText,
                    fontPath,
                    config.fontColor,
                    config.fontSize,
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

            String frpcPath = findFrpc();
            if (frpcPath == null) {
                log("❌ 未找到frpc，请下载FRP客户端");
                showAlert("FRP未找到", "请下载frpc并放到libs目录或添加到PATH");
                return;
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

    private String findFrpc() {
        String[] possiblePaths = {
                "./libs/frpc/frpc.exe",
                "./libs/frpc/frpc",
                "./frpc.exe",
                "./frpc",
                "frpc.exe",
                "frpc"
        };

        for (String path : possiblePaths) {
            if (Files.exists(Paths.get(path))) {
                return path;
            }
        }
        return null;
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