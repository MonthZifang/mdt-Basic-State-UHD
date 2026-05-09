package mdt;

import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Json;

public class PluginConfig{
    public int virtualPlayers = 0;
    public JoinPopup joinPopup = new JoinPopup();
    public MapVote mapVote = new MapVote();
    public StatusBar statusBar = new StatusBar();
    public ExternalCommandRegistry externalCommandRegistry = new ExternalCommandRegistry();

    public static PluginConfig load(Fi file){
        PluginConfig defaults = new PluginConfig();
        defaults.sanitize();

        if(file == null){
            return defaults;
        }

        Json json = new Json();
        json.setIgnoreUnknownFields(true);

        try{
            if(file.parent() != null){
                file.parent().mkdirs();
            }

            if(!file.exists()){
                writeExpandedConfig(file, defaults);
                return defaults;
            }

            String raw = file.readString("UTF-8");
            PluginConfig loaded = json.fromJson(PluginConfig.class, raw);
            if(loaded == null){
                writeExpandedConfig(file, defaults);
                return defaults;
            }

            loaded.sanitize();

            if(shouldRewriteExpandedConfig(raw)){
                writeExpandedConfig(file, loaded);
            }

            return loaded;
        }catch(Throwable t){
            Log.err("Failed to load serve-mdt plugin config, rewriting defaults.");
            Log.err(t);
            try{
                writeExpandedConfig(file, defaults);
            }catch(Throwable writeError){
                Log.err("Failed to rewrite default config.");
                Log.err(writeError);
            }
            return defaults;
        }
    }

    private static boolean shouldRewriteExpandedConfig(String raw){
        String value = raw == null ? "" : raw;
        return !value.contains("delayMs")
            || !value.contains("announcementText")
            || !value.contains("durationSec")
            || !value.contains("homeLinkUrl")
            || !value.contains("homeLinkLabel")
            || !value.contains("refreshIntervalSec")
            || !value.contains("serverNameFormat")
            || !value.contains("externalCommandRegistry")
            || !value.contains("pluginsRootPath")
            || !value.contains("includePluginNameInLabel");
    }

    private static void writeExpandedConfig(Fi file, PluginConfig config){
        config.sanitize();
        file.writeString(renderConfig(config), false, "UTF-8");
    }

    private static String renderConfig(PluginConfig cfg){
        StringBuilder out = new StringBuilder(4096);
        out.append("{\n");
        appendInt(out, "virtualPlayers", cfg.virtualPlayers, 1, true);

        out.append("  \"joinPopup\": {\n");
        appendBoolean(out, "enabled", cfg.joinPopup.enabled, 2, true);
        appendInt(out, "delayMs", cfg.joinPopup.delayMs, 2, true);
        appendString(out, "title", cfg.joinPopup.title, 2, true);
        appendString(out, "message", cfg.joinPopup.message, 2, true);
        appendString(out, "announcementText", cfg.joinPopup.announcementText, 2, true);
        appendString(out, "linkUrl", cfg.joinPopup.linkUrl, 2, true);
        appendString(out, "helpText", cfg.joinPopup.helpText, 2, false);
        out.append("  },\n");

        out.append("  \"mapVote\": {\n");
        appendInt(out, "durationSec", cfg.mapVote.durationSec, 2, true);
        appendInt(out, "statusRefreshMs", cfg.mapVote.statusRefreshMs, 2, true);
        appendInt(out, "popupDurationMs", cfg.mapVote.popupDurationMs, 2, true);
        appendString(out, "homeLinkUrl", cfg.mapVote.homeLinkUrl, 2, true);
        appendString(out, "homeLinkLabel", cfg.mapVote.homeLinkLabel, 2, true);
        appendString(out, "align", cfg.mapVote.align, 2, true);
        appendInt(out, "top", cfg.mapVote.top, 2, true);
        appendInt(out, "left", cfg.mapVote.left, 2, true);
        appendInt(out, "bottom", cfg.mapVote.bottom, 2, true);
        appendInt(out, "right", cfg.mapVote.right, 2, false);
        out.append("  },\n");

        out.append("  \"externalCommandRegistry\": {\n");
        appendBoolean(out, "enabled", cfg.externalCommandRegistry.enabled, 2, true);
        appendString(out, "pluginsRootPath", cfg.externalCommandRegistry.pluginsRootPath, 2, true);
        appendBoolean(out, "includePluginNameInLabel", cfg.externalCommandRegistry.includePluginNameInLabel, 2, false);
        out.append("  },\n");

        out.append("  \"statusBar\": {\n");
        appendBoolean(out, "enabled", cfg.statusBar.enabled, 2, true);
        appendInt(out, "refreshIntervalSec", cfg.statusBar.refreshIntervalSec, 2, true);
        appendInt(out, "popupDurationMs", cfg.statusBar.popupDurationMs, 2, true);
        appendString(out, "align", cfg.statusBar.align, 2, true);
        appendInt(out, "top", cfg.statusBar.top, 2, true);
        appendInt(out, "left", cfg.statusBar.left, 2, true);
        appendInt(out, "bottom", cfg.statusBar.bottom, 2, true);
        appendInt(out, "right", cfg.statusBar.right, 2, true);
        appendString(out, "popupId", cfg.statusBar.popupId, 2, true);
        appendBoolean(out, "headerEnabled", cfg.statusBar.headerEnabled, 2, true);
        appendString(out, "headerText", cfg.statusBar.headerText, 2, true);
        appendBoolean(out, "serverNameEnabled", cfg.statusBar.serverNameEnabled, 2, true);
        appendString(out, "serverNameFormat", cfg.statusBar.serverNameFormat, 2, true);
        appendBoolean(out, "performanceEnabled", cfg.statusBar.performanceEnabled, 2, true);
        appendString(out, "performanceFormat", cfg.statusBar.performanceFormat, 2, true);
        appendBoolean(out, "currentMapEnabled", cfg.statusBar.currentMapEnabled, 2, true);
        appendString(out, "currentMapFormat", cfg.statusBar.currentMapFormat, 2, true);
        appendBoolean(out, "gameTimeEnabled", cfg.statusBar.gameTimeEnabled, 2, true);
        appendString(out, "gameTimeFormat", cfg.statusBar.gameTimeFormat, 2, true);
        appendBoolean(out, "playerCountEnabled", cfg.statusBar.playerCountEnabled, 2, true);
        appendString(out, "playerCountFormat", cfg.statusBar.playerCountFormat, 2, true);
        appendBoolean(out, "welcomeEnabled", cfg.statusBar.welcomeEnabled, 2, true);
        appendString(out, "welcomeFormat", cfg.statusBar.welcomeFormat, 2, true);
        appendBoolean(out, "qqGroupEnabled", cfg.statusBar.qqGroupEnabled, 2, true);
        appendString(out, "qqGroupText", cfg.statusBar.qqGroupText, 2, true);
        appendString(out, "qqGroupFormat", cfg.statusBar.qqGroupFormat, 2, true);
        appendBoolean(out, "customMessageEnabled", cfg.statusBar.customMessageEnabled, 2, true);
        appendString(out, "customMessageText", cfg.statusBar.customMessageText, 2, true);
        appendString(out, "customMessageFormat", cfg.statusBar.customMessageFormat, 2, false);
        out.append("  }\n");
        out.append("}\n");
        return out.toString();
    }

    private static void appendIndent(StringBuilder out, int level){
        for(int i = 0; i < level; i++){
            out.append("  ");
        }
    }

    private static void appendBoolean(StringBuilder out, String key, boolean value, int indent, boolean comma){
        appendIndent(out, indent);
        out.append('"').append(key).append("\": ").append(value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void appendInt(StringBuilder out, String key, int value, int indent, boolean comma){
        appendIndent(out, indent);
        out.append('"').append(key).append("\": ").append(value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void appendString(StringBuilder out, String key, String value, int indent, boolean comma){
        appendIndent(out, indent);
        out.append('"').append(key).append("\": ").append('"').append(escape(value)).append('"');
        out.append(comma ? ",\n" : "\n");
    }

    private static String escape(String value){
        String text = value == null ? "" : value;
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
    }

    public void sanitize(){
        virtualPlayers = Math.max(virtualPlayers, 0);

        if(joinPopup == null){
            joinPopup = new JoinPopup();
        }
        if(mapVote == null){
            mapVote = new MapVote();
        }
        if(statusBar == null){
            statusBar = new StatusBar();
        }
        if(externalCommandRegistry == null){
            externalCommandRegistry = new ExternalCommandRegistry();
        }

        joinPopup.sanitize();
        mapVote.sanitize();
        externalCommandRegistry.sanitize();
        statusBar.sanitize();
    }

    public static class JoinPopup{
        public boolean enabled = true;
        public int delayMs = 100;
        public String title = "[accent]服务器公告[]";
        public String message =
            "欢迎 [green]{player_name}[] 来到 [white]{server_name}[]\n" +
            "当前地图: [white]{current_map}[]\n" +
            "请选择下方按钮。";
        public String announcementText =
            "[accent]服务器公告[]\n\n" +
            "1. 如果有任何问题请转移到交流群333641130\n" +
            "2. 请保持友好的交流";
        public String linkUrl = "https://qm.qq.com/q/Op6STL0WMG";
        public String helpText =
            "[accent]可用命令[]\n" +
            "/help 打开帮助菜单\n" +
            "/votemap 打开换图投票菜单\n" +
            "/vote 查看当前换图投票\n" +
            "/kill 清除当前单位";

        public void sanitize(){
            delayMs = Math.max(delayMs, 0);
            title = safe(title, "[accent]服务器公告[]");
            message = safe(message, "");
            announcementText = safe(announcementText, "");
            linkUrl = safe(linkUrl, "");
            helpText = safe(helpText, "");
        }
    }

    public static class MapVote{
        public int durationSec = 15;
        public int statusRefreshMs = 1500;
        public int popupDurationMs = 1800;
        public String homeLinkUrl = "https://cn.mindustry.top";
        public String homeLinkLabel = "WZ资源站";
        public String align = "top_left";
        public int top = 220;
        public int left = 0;
        public int bottom = 0;
        public int right = 0;

        public void sanitize(){
            durationSec = Math.max(durationSec, 5);
            statusRefreshMs = Math.max(statusRefreshMs, 500);
            popupDurationMs = Math.max(popupDurationMs, 800);
            homeLinkUrl = safe(homeLinkUrl, "");
            homeLinkLabel = safe(homeLinkLabel, "WZ资源站");
            align = safe(align, "top_left");
        }
    }

    public static class ExternalCommandRegistry{
        public boolean enabled = true;
        public String pluginsRootPath = "C:/Users/43551/Desktop/mdt-Plugin/plugins";
        public boolean includePluginNameInLabel = true;

        public void sanitize(){
            pluginsRootPath = safe(pluginsRootPath, "C:/Users/43551/Desktop/mdt-Plugin/plugins");
        }
    }

    public static class StatusBar{
        public boolean enabled = true;
        public int refreshIntervalSec = 2;
        public int popupDurationMs = 2200;
        public String align = "top_left";
        public int top = 155;
        public int left = 0;
        public int bottom = 0;
        public int right = 0;
        public String popupId = "server-status-bar";
        public boolean headerEnabled = true;
        public String headerText = "[accent]服务器状态[]";
        public boolean serverNameEnabled = true;
        public String serverNameFormat = "[green]服务器: [white]{server_name}[]";
        public boolean performanceEnabled = true;
        public String performanceFormat = "[green]性能: [white]CPU {cpu_percent}%[] [white]进程内存 {memory_mb} MB[]";
        public boolean currentMapEnabled = true;
        public String currentMapFormat = "[green]当前地图: [white]{current_map}[]";
        public boolean gameTimeEnabled = true;
        public String gameTimeFormat = "[green]本局时间: [white]{game_time}[]";
        public boolean playerCountEnabled = true;
        public String playerCountFormat = "[green]在线人数: [white]{players}[]";
        public boolean welcomeEnabled = true;
        public String welcomeFormat = "[gold]欢迎玩家 {player_name} 来到镜像物语[]";
        public boolean qqGroupEnabled = true;
        public String qqGroupText = "333641130";
        public String qqGroupFormat = "[green]QQ群: [white]{qq_group}[]";
        public boolean customMessageEnabled = true;
        public String customMessageText = "这是一个重构项目，欢迎加入测试。";
        public String customMessageFormat = "[gold]{message}[]";

        public void sanitize(){
            refreshIntervalSec = Math.max(refreshIntervalSec, 1);
            popupDurationMs = Math.max(popupDurationMs, 800);
            align = safe(align, "top_left");
            popupId = safe(popupId, "server-status-bar");
            headerText = safe(headerText, "");
            serverNameFormat = safe(serverNameFormat, "");
            performanceFormat = safe(performanceFormat, "");
            currentMapFormat = safe(currentMapFormat, "");
            gameTimeFormat = safe(gameTimeFormat, "");
            playerCountFormat = safe(playerCountFormat, "");
            welcomeFormat = safe(welcomeFormat, "");
            qqGroupText = safe(qqGroupText, "");
            qqGroupFormat = safe(qqGroupFormat, "");
            customMessageText = safe(customMessageText, "");
            customMessageFormat = safe(customMessageFormat, "");
        }
    }

    private static String safe(String value, String fallback){
        return value == null ? fallback : value;
    }
}
