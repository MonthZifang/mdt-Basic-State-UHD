package mdt;

import arc.Events;
import arc.files.Fi;
import arc.util.CommandHandler;
import arc.util.CommandHandler.CommandResponse;
import arc.util.CommandHandler.ResponseType;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Timer;
import com.sun.management.OperatingSystemMXBean;
import mindustry.Vars;
import mindustry.game.EventType.MenuOptionChooseEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.TextInputEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.maps.Map;
import mindustry.mod.Plugin;
import mindustry.net.Administration;
import mindustry.net.WorldReloader;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServeMdtPlugin extends Plugin{
    private static final String PLUGIN_NAME = "mdt-basic-state-uhd";
    private static final String LEGACY_CONFIG_FOLDER_NAME = "ServeMdtPlugin";
    private static final int JOIN_MENU_ID = 910001;
    private static final int HELP_PAGE_BASE_MENU_ID = 910020;
    private static final int HELP_PAGE_SIZE = 8;
    private static final int MAP_VOTE_SELECT_MENU_BASE_ID = 910100;
    private static final int MAP_VOTE_PROMPT_MENU_ID = 910200;
    private static final int MAP_VOTE_SEARCH_INPUT_ID = 910201;
    private static final int MAP_PAGE_SIZE = 4;
    private static final ConcurrentHashMap<String, Object> SHARED_SERVICES = new ConcurrentHashMap<String, Object>();

    private final Object voteLock = new Object();
    private final ProcessCpuTracker cpuTracker = new ProcessCpuTracker();
    private final long pluginStartMillis = System.currentTimeMillis();
    private final List<HelpCommandButton> externalHelpButtons = new ArrayList<HelpCommandButton>();
    private final ConcurrentHashMap<String, MapBrowserState> mapBrowserStates = new ConcurrentHashMap<String, MapBrowserState>();
    private final ConcurrentHashMap<String, String> helpNotes = new ConcurrentHashMap<String, String>();

    private PluginConfig config;
    private VoteState activeVote;
    private Timer.Task voteTimeoutTask;
    private Timer.Task voteHudTask;
    private Timer.Task statusBarTask;
    private int voteSequence;
    private String externalCommandRegistryStatus = "not-loaded";

    private static final Pattern REGISTER_WITH_USAGE_PATTERN = Pattern.compile(
        "handler\\s*\\.\\s*<Player>register\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,"
    );
    private static final Pattern REGISTER_NO_USAGE_PATTERN = Pattern.compile(
        "handler\\s*\\.\\s*<Player>register\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,"
    );
    private static final Pattern JSON_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    public static void registerSharedService(String key, Object service){
        if(key == null){
            return;
        }
        String normalized = key.trim();
        if(normalized.isEmpty()){
            return;
        }
        if(service == null){
            SHARED_SERVICES.remove(normalized);
            return;
        }
        SHARED_SERVICES.put(normalized, service);
    }

    public static void unregisterSharedService(String key){
        if(key == null){
            return;
        }
        String normalized = key.trim();
        if(normalized.isEmpty()){
            return;
        }
        SHARED_SERVICES.remove(normalized);
    }

    public static Object getSharedService(String key){
        if(key == null){
            return null;
        }
        String normalized = key.trim();
        if(normalized.isEmpty()){
            return null;
        }
        return SHARED_SERVICES.get(normalized);
    }

    public static java.util.Map<String, Object> snapshotSharedServices(){
        return new HashMap<String, Object>(SHARED_SERVICES);
    }

    @Override
    public void init(){
        reloadConfig();

        Events.on(PlayerJoin.class, event -> {
            updatePlayerProfileTimestamps(event.player);
            scheduleJoinPopup(event.player);
        });
        Events.on(MenuOptionChooseEvent.class, event -> handleMenuOption(event.player, event.menuId, event.option));
        Events.on(TextInputEvent.class, event -> handleTextInput(event.player, event.textInputId, event.text));
        Events.on(WorldLoadEvent.class, event -> clearVoteState());
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("mdtreload", "Reload serve-mdt plugin config from disk.", args -> {
            reloadConfig();
            Log.info("serve-mdt plugin config reloaded.");
        });

        handler.register("mdtregister", "Reload external help command registry.", args -> {
            reloadExternalHelpRegistry();
            Log.info("serve-mdt external command registry reloaded. status=@ count=@", externalCommandRegistryStatus, externalHelpButtons.size());
        });

        handler.register("mdt-services", "\u67e5\u770b\u5df2\u516c\u5f00\u7684 MDT \u5171\u4eab\u670d\u52a1\u3002", args -> {
            java.util.Map<String, Object> services = snapshotSharedServices();
            if(services.isEmpty()){
                Log.info("MDT \u5171\u4eab\u670d\u52a1\u5217\u8868\u4e3a\u7a7a\u3002");
                return;
            }
            List<String> keys = new ArrayList<String>(services.keySet());
            Collections.sort(keys);
            Log.info("MDT \u5171\u4eab\u670d\u52a1 @ \u9879:", keys.size());
            for(String key : keys){
                Object value = services.get(key);
                Log.info("  @ -> @", key, value == null ? "<null>" : value.getClass().getName());
            }
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("help", "Open the serve-mdt help menu.", (args, player) -> {
            if(player != null){
                showHelpPageMenu(player, 0);
            }
        });
        handler.<Player>register("帮助", "打开基础帮助菜单。", (args, player) -> {
            if(player != null){
                showHelpPageMenu(player, 0);
            }
        });

        handler.<Player>register("kill", "Clear your current unit.", (args, player) -> {
            if(player != null){
                clearPlayerUnit(player);
            }
        });

        handler.<Player>register("vote", "[yes/no/neutral]", "Show current map vote or vote on it.", (args, player) -> {
            if(player == null){
                return;
            }

            if(args.length == 1){
                byte parsed = parseVoteKeyword(args[0]);
                if(parsed != Byte.MIN_VALUE){
                    castVote(player, parsed);
                    return;
                }
            }

            showActiveVoteMenu(player, getVoteStatus());
        });

        handler.<Player>register("votemap", "[map/yes/no/neutral...]", "Open map vote menu or start/vote.", (args, player) -> {
            if(player == null){
                return;
            }

            if(args.length == 0){
                showMapVoteBrowser(player, 0);
                return;
            }

            if(args.length == 1){
                byte parsed = parseVoteKeyword(args[0]);
                if(parsed != Byte.MIN_VALUE){
                    castVote(player, parsed);
                    return;
                }
            }

            if(isMapSearchShortcut(args)){
                openMapSearch(player, extractMapSearchQuery(args));
                return;
            }

            openMapFromQuery(player, joinArgs(args));
        });

        handler.<Player>register("changemap", "<map...>", "Admin only. Change to a map immediately.", (args, player) -> {
            if(player == null){
                return;
            }
            if(!player.admin()){
                player.sendMessage("[scarlet]只有管理员可以直接换图。[]");
                return;
            }

            Map map = findMap(joinArgs(args));
            if(map == null){
                player.sendMessage("[scarlet]没有找到地图:[] " + joinArgs(args));
                return;
            }

            Call.sendMessage("[accent]" + displayPlayerName(player) + "[] 正在切换地图到 [white]" + map.plainName() + "[]");
            loadMap(map);
        });
    }

    private void reloadConfig(){
        Fi configFile = resolveConfigFile();
        migrateLegacyConfigIfNeeded(configFile);
        config = PluginConfig.load(configFile);
        reloadHelpNotes();
        reloadExternalHelpRegistry();
        restartStatusBarLoop();
    }

    private void reloadHelpNotes(){
        helpNotes.clear();
        loadHelpNotesFile(new Fi("config").child("mods").child("config").child("mdt-help-classification-strategy").child("native-help-zh.properties"));
        loadHelpNotesFile(new Fi("config").child("mods").child("config").child("mdt-help-list-layout").child("help-list-notes-zh.properties"));
    }

    private void loadHelpNotesFile(Fi file){
        if(file == null || !file.exists()){
            return;
        }

        java.util.Properties properties = new java.util.Properties();
        try(java.io.InputStreamReader reader = new java.io.InputStreamReader(new java.io.FileInputStream(file.file()), java.nio.charset.StandardCharsets.UTF_8)){
            properties.load(reader);
        }catch(Throwable t){
            Log.warn("Failed to load help notes from @: @", file.absolutePath(), t.toString());
            return;
        }

        for(String name : properties.stringPropertyNames()){
            String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            String value = properties.getProperty(name, "").trim();
            if(!key.isEmpty() && !value.isEmpty()){
                helpNotes.put(key, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updatePlayerProfileTimestamps(Player player){
        if(player == null){
            return;
        }
        try{
            String uuid = resolvePlayerUuid(player);
            String comId = resolvePlayerComId(uuid);
            if(uuid == null || uuid.trim().isEmpty() || comId == null || comId.trim().isEmpty()){
                return;
            }

            Object listData = getSharedService("mdt.listdata.api");
            if(listData == null){
                listData = getSharedService("com.mdt.listdata.api.ListDataSystemApi");
            }
            if(listData == null){
                return;
            }

            java.lang.reflect.Method getObject = listData.getClass().getMethod("getObject", String.class, String.class);
            java.lang.reflect.Method putObject = listData.getClass().getMethod("putObject", String.class, String.class, java.util.Map.class);
            Object current = getObject.invoke(listData, "player_profile", comId);
            java.util.Map<String, String> values = current instanceof java.util.Map
                ? new HashMap<String, String>((java.util.Map<String, String>)current)
                : new HashMap<String, String>();

            String now = utcNow();
            values.put("comId", comId);
            values.put("playerUuid", uuid);
            values.put("playerName", displayPlayerName(player));
            if(!values.containsKey("firstJoinAt") || values.get("firstJoinAt") == null || values.get("firstJoinAt").trim().isEmpty()){
                values.put("firstJoinAt", now);
            }
            values.put("lastLoginAt", now);
            values.put("updatedAt", now);
            putObject.invoke(listData, "player_profile", comId, values);
        }catch(Throwable t){
            Log.warn("Failed to update player_profile timestamps: @", t.toString());
        }
    }

    private String resolvePlayerUuid(Player player){
        try{
            return String.valueOf(player.uuid());
        }catch(Throwable ignored){
            try{
                java.lang.reflect.Field field = player.getClass().getField("uuid");
                Object value = field.get(player);
                return value == null ? "" : value.toString();
            }catch(Throwable t){
                return "";
            }
        }
    }

    private String resolvePlayerComId(String uuid){
        if(uuid == null || uuid.trim().isEmpty()){
            return "";
        }
        try{
            Object jumpApi = getSharedService("mdt.jump.api");
            if(jumpApi == null){
                jumpApi = getSharedService("com.mdt.jump.api.JumpComIdApi");
            }
            if(jumpApi == null){
                return "";
            }
            java.lang.reflect.Method getOrCreate = jumpApi.getClass().getMethod("getOrCreate", String.class);
            Object record = getOrCreate.invoke(jumpApi, uuid);
            if(record == null){
                return "";
            }
            java.lang.reflect.Method getComId = record.getClass().getMethod("getComId");
            Object value = getComId.invoke(record);
            return value == null ? "" : value.toString();
        }catch(Throwable t){
            return "";
        }
    }

    private String utcNow(){
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date());
    }

    private Fi resolveConfigFile(){
        Fi folder = new Fi("config").child("mods").child("config").child(PLUGIN_NAME);
        folder.mkdirs();
        return folder.child("config.json");
    }

    private void migrateLegacyConfigIfNeeded(Fi configFile){
        if(configFile == null || configFile.exists()){
            return;
        }

        Fi legacy = new Fi("config").child(LEGACY_CONFIG_FOLDER_NAME).child("config.json");
        if(legacy == null || !legacy.exists()){
            return;
        }

        try{
            if(configFile.parent() != null){
                configFile.parent().mkdirs();
            }
            configFile.writeString(legacy.readString("UTF-8"), false, "UTF-8");
            legacy.delete();
        }catch(Throwable t){
            Log.err("Failed to migrate legacy plugin config.");
            Log.err(t);
        }
    }

    private void restartStatusBarLoop(){
        if(statusBarTask != null){
            statusBarTask.cancel();
            statusBarTask = null;
        }

        if(config == null || !config.statusBar.enabled){
            return;
        }

        float refresh = Math.max(config.statusBar.refreshIntervalSec, 1);
        statusBarTask = Timer.schedule(this::broadcastStatusBar, refresh, refresh);
    }

    private void scheduleJoinPopup(Player player){
        if(player == null || player.con == null || config == null || !config.joinPopup.enabled){
            return;
        }

        float delaySeconds = Math.max(config.joinPopup.delayMs, 0) / 1000f;
        Timer.schedule(() -> {
            if(player.con != null){
                showJoinPopup(player);
            }
        }, delaySeconds);
    }

    private void showJoinPopup(Player player){
        Call.menu(
            player.con,
            JOIN_MENU_ID,
            renderJoinPopupValue(config.joinPopup.title, player),
            buildJoinPopupMessage(player),
            new String[][]{
                {"打开链接", "帮助"},
                {"投票换图", "关闭"}
            }
        );
    }

    private String buildJoinPopupMessage(Player player){
        String intro = renderJoinPopupValue(config.joinPopup.message, player).trim();
        String announcement = renderJoinPopupValue(config.joinPopup.announcementText, player).trim();

        if(intro.isEmpty()){
            return announcement;
        }
        if(announcement.isEmpty()){
            return intro;
        }
        return intro + "\n\n" + announcement;
    }

    private String renderJoinPopupValue(String raw, Player player){
        String value = raw == null ? "" : raw.trim();
        if(value.isEmpty()){
            return "";
        }

        return value
            .replace("{server_name}", currentServerName())
            .replace("{player_name}", currentPlayerName(player))
            .replace("{current_map}", currentMapName())
            .replace("{players}", Integer.toString(displayPlayerCount()))
            .replace("{link_url}", config.joinPopup.linkUrl == null ? "" : config.joinPopup.linkUrl.trim());
    }

    private void showHelpPageMenu(Player player, int page){
        if(player == null || player.con == null){
            return;
        }

        List<HelpPage> pages = helpPages(player);
        if(pages.isEmpty()){
            return;
        }

        int currentPage = clampPage(page, pages.size());
        HelpPage current = pages.get(currentPage);

        Call.menu(
            player.con,
            HELP_PAGE_BASE_MENU_ID + currentPage,
            current.title,
            current.message,
            buildHelpPageOptions(current, currentPage, pages.size())
        );
    }

    private List<HelpPage> helpPages(Player player){
        List<HelpCommandButton> buttons = mergedHelpCommandButtons();
        int totalPages = Math.max((buttons.size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE, 1);
        List<HelpPage> pages = new ArrayList<HelpPage>(totalPages);

        for(int page = 0; page < totalPages; page++){
            int start = page * HELP_PAGE_SIZE;
            int end = Math.min(start + HELP_PAGE_SIZE, buttons.size());

            List<String> lines = new ArrayList<String>();
            if(page == 0 && config != null && config.joinPopup != null && !config.joinPopup.helpText.trim().isEmpty()){
                lines.add(renderJoinPopupValue(config.joinPopup.helpText, player));
                lines.add("");
            }
            lines.add("[accent]点击下方按钮会直接执行已接入的命令。[]");
            lines.add("[gray]需要参数的命令会显示用途说明，说明文字会优先使用中文注释。[]");

            if(page == 0){
                String statusSummary = buildHelpStatusSummary(player);
                if(!statusSummary.isEmpty()){
                    lines.add(statusSummary);
                    lines.add("");
                }
            }
            lines.add("[lightgray]registry: " + externalCommandRegistryStatus + "[]");
            lines.add("");
            appendHelpCommandSummaries(lines, buttons, start, end);

            pages.add(new HelpPage(
                "[accent]帮助 " + (page + 1) + "/" + totalPages + "[]",
                compactHelpLines(lines),
                new ArrayList<HelpCommandButton>(buttons.subList(start, end))
            ));
        }

        return pages;
    }

    private List<HelpCommandButton> helpCommandButtons(){
        List<HelpCommandButton> buttons = new ArrayList<HelpCommandButton>();
        buttons.add(createBuiltInHelpButton("votemap", "<map|search>", "打开地图投票界面，可搜索地图关键词", "/votemap", "[accent]Usage:[] /votemap or /votemap <map keyword>"));
        buttons.add(createBuiltInHelpButton("vote", "[yes/no/neutral]", "查看当前投票并参与投票", "/vote", "[accent]Usage:[] /vote or /vote yes"));
        buttons.add(createBuiltInHelpButton("kill", "", "清除自己当前控制的单位", "/kill", null));
        return buttons;
    }

    private HelpCommandButton createBuiltInHelpButton(String command, String args, String fallbackDescription, String runText, String usageMessage){
        String description = resolveHelpDescription(command, fallbackDescription);
        String label = buildHelpButtonLabel(command, args, description);
        return new HelpCommandButton(label, command, args, description, "serve-mdt", runText, usageMessage, null);
    }

    private List<HelpCommandButton> mergedHelpCommandButtons(){
        List<HelpCommandButton> buttons = new ArrayList<HelpCommandButton>(helpCommandButtons());
        synchronized(externalHelpButtons){
            for(HelpCommandButton button : externalHelpButtons){
                if(!containsCommand(buttons, button.command)){
                    buttons.add(button);
                }
            }
        }
        Collections.sort(buttons, Comparator.comparing(button -> button.command.toLowerCase(Locale.ROOT)));
        return buttons;
    }

    private boolean containsCommand(List<HelpCommandButton> buttons, String command){
        for(HelpCommandButton button : buttons){
            if(button.command.equalsIgnoreCase(command)){
                return true;
            }
        }
        return false;
    }

    private void appendHelpCommandSummaries(List<String> lines, List<HelpCommandButton> buttons, int start, int end){
        for(int idx = start; idx < end; idx++){
            HelpCommandButton button = buttons.get(idx);
            StringBuilder line = new StringBuilder();
            line.append("[accent]/").append(button.command).append("[]");
            if(button.args != null && !button.args.isEmpty()){
                line.append(" [lightgray]").append(button.args).append("[]");
            }
            if(button.description != null && !button.description.isEmpty()){
                line.append(" - ").append(button.description);
            }
            if(button.source != null && !button.source.isEmpty()){
                line.append(" [gray](").append(button.source).append(")[]");
            }
            lines.add(line.toString());
        }
    }

    private HelpCommandButton createExternalHelpButton(String command, String args, String description, String source){
        String safeCommand = command == null ? "" : command.trim();
        String safeArgs = args == null ? "" : args.trim();
        String safeDescription = resolveHelpDescription(safeCommand, description == null ? "" : description.trim());
        String safeSource = source == null ? "" : source.trim();
        String label = buildHelpButtonLabel(safeCommand, safeArgs, safeDescription);
        if(config != null && config.externalCommandRegistry != null && config.externalCommandRegistry.includePluginNameInLabel && !safeSource.isEmpty()){
            label += "\n[gray]" + safeSource + "[]";
        }
        String usageMessage = safeArgs.isEmpty() ? null : "[accent]Usage:[] /" + safeCommand + " " + safeArgs;
        return new HelpCommandButton(label, safeCommand, safeArgs, safeDescription, safeSource, "/" + safeCommand, usageMessage, null);
    }

    private String buildHelpButtonLabel(String command, String args, String description){
        String safeCommand = command == null ? "" : command.trim();
        String safeArgs = args == null ? "" : args.trim();
        String safeDescription = description == null ? "" : description.trim();
        if(safeDescription.isEmpty()){
            return safeArgs.isEmpty() ? "/" + safeCommand : "/" + safeCommand + " " + safeArgs;
        }

        StringBuilder label = new StringBuilder(safeDescription);
        label.append("\n[lightgray]/").append(safeCommand);
        if(!safeArgs.isEmpty()){
            label.append(" ").append(safeArgs);
        }
        label.append("[]");
        return label.toString();
    }

    private String resolveHelpDescription(String command, String fallback){
        String key = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if(!key.isEmpty()){
            String note = helpNotes.get(key);
            if(note != null && !note.trim().isEmpty()){
                return note.trim();
            }
        }
        return fallback == null ? "" : fallback.trim();
    }

    private String shortenLabel(String value){
        String safe = value == null ? "" : value.trim();
        if(safe.length() <= 12){
            return safe;
        }
        return safe.substring(0, 12);
    }

    private String[][] buildHelpPageOptions(HelpPage page, int index, int totalPages){
        List<String[]> rows = new ArrayList<String[]>();
        for(HelpCommandButton button : page.buttons){
            rows.add(new String[]{button.label});
        }

        String prevLabel = index > 0 ? "上一页" : "[gray]上一页[]";
        String nextLabel = index + 1 < totalPages ? "下一页" : "[gray]下一页[]";
        rows.add(new String[]{prevLabel, "关闭", nextLabel});
        return rows.toArray(new String[rows.size()][]);
    }

    private String compactHelpLines(List<String> lines){
        StringBuilder builder = new StringBuilder();
        boolean previousBlank = true;

        for(String line : lines){
            String trimmed = line == null ? "" : line.trim();
            if(trimmed.isEmpty()){
                if(!previousBlank && builder.length() > 0){
                    builder.append('\n');
                }
                previousBlank = true;
                continue;
            }

            if(builder.length() > 0){
                builder.append('\n');
            }
            builder.append(line);
            previousBlank = false;
        }

        return builder.toString();
    }

    private void handleMenuOption(Player player, int menuId, int option){
        if(player == null || option < 0){
            return;
        }

        if(menuId == JOIN_MENU_ID){
            switch(option){
                case 0:
                    openJoinLink(player);
                    return;
                case 1:
                    showHelpPageMenu(player, 0);
                    return;
                case 2:
                    showMapVoteBrowser(player, 0);
                    return;
                default:
                    return;
            }
        }

        if(menuId >= HELP_PAGE_BASE_MENU_ID && menuId < HELP_PAGE_BASE_MENU_ID + 100){
            handleHelpPageChoice(player, menuId, option);
            return;
        }

        if(menuId == MAP_VOTE_PROMPT_MENU_ID){
            ExternalVoteSession external = getExternalVoteSession();
            if(external != null){
                switch(option){
                    case 0:
                        castExternalVote(player, (byte)1, external);
                        return;
                    case 1:
                        castExternalVote(player, (byte)-1, external);
                        return;
                    default:
                        return;
                }
            }
            switch(option){
                case 0:
                    castVote(player, (byte)1);
                    return;
                case 1:
                    castVote(player, (byte)-1);
                    return;
                case 2:
                    castVote(player, (byte)0);
                    return;
                default:
                    return;
            }
        }

        if(menuId >= MAP_VOTE_SELECT_MENU_BASE_ID && menuId < MAP_VOTE_SELECT_MENU_BASE_ID + 1000){
            handleMapBrowserSelection(player, menuId - MAP_VOTE_SELECT_MENU_BASE_ID, option);
        }
    }

    private void handleTextInput(Player player, int textInputId, String text){
        if(textInputId == MAP_VOTE_SEARCH_INPUT_ID){
            handleMapVoteTextInput(player, text);
        }
    }

    private void handleMapBrowserSelection(Player player, int page, int option){
        handleMapSelection(player, page, option);
    }

    private void handleHelpPageChoice(Player player, int menuId, int option){
        List<HelpPage> pages = helpPages(player);
        if(pages.isEmpty()){
            return;
        }

        int page = menuId - HELP_PAGE_BASE_MENU_ID;
        if(page < 0 || page >= pages.size()){
            return;
        }

        HelpPage current = pages.get(page);
        if(option < current.buttons.size()){
            runHelpButton(player, current.buttons.get(option));
            return;
        }

        int nav = option - current.buttons.size();
        switch(nav){
            case 0:
                if(page > 0){
                    showHelpPageMenu(player, page - 1);
                }
                return;
            case 2:
                if(page + 1 < pages.size()){
                    showHelpPageMenu(player, page + 1);
                }
                return;
            default:
                return;
        }
    }

    private void runHelpButton(Player player, HelpCommandButton button){
        if(player == null || button == null){
            return;
        }

        if(button.action != null){
            button.action.run(player);
            return;
        }

        if(button.runText != null && !button.runText.trim().isEmpty() && Vars.netServer != null && Vars.netServer.clientCommands != null){
            CommandResponse response = Vars.netServer.clientCommands.handleMessage(button.runText, player);
            if(response != null && response.type == ResponseType.valid){
                return;
            }
        }

        if(button.usageMessage != null && !button.usageMessage.trim().isEmpty()){
            player.sendMessage(button.usageMessage);
        }else{
            player.sendMessage("[scarlet]该按钮在当前 Java 插件中不可用。[]");
        }
    }

    private void openJoinLink(Player player){
        String link = normalizeUrl(config.joinPopup.linkUrl);
        if(link.isEmpty()){
            player.sendMessage("[scarlet]当前未配置公告链接。[]");
            return;
        }
        Call.openURI(player.con, link);
    }

    private void clearPlayerUnit(Player player){
        Unit unit = player == null ? null : player.unit();
        if(unit == null || unit.dead()){
            player.sendMessage("[scarlet]当前没有可清除的单位。[]");
            return;
        }

        unit.kill();
        player.clearUnit();
        player.sendMessage("[accent]已清除当前单位。[]");
    }

    private void showMapVoteMenu(Player player, int page){
        if(player == null || player.con == null){
            return;
        }

        ExternalVoteSession external = getExternalVoteSession();
        if(external != null){
            showExternalVoteMenu(player, external);
            return;
        }

        VoteStatus active = getVoteStatus();
        if(active != null){
            showActiveVoteMenu(player, active);
            return;
        }

        List<Map> maps = availableMaps();
        if(maps.isEmpty()){
            player.sendMessage("[scarlet]当前没有可投票的地图。[]");
            return;
        }

        int totalPages = Math.max((maps.size() + MAP_PAGE_SIZE - 1) / MAP_PAGE_SIZE, 1);
        int currentPage = clampPage(page, totalPages);
        int start = currentPage * MAP_PAGE_SIZE;
        int end = Math.min(start + MAP_PAGE_SIZE, maps.size());
        List<Map> pageMaps = maps.subList(start, end);

        StringBuilder message = new StringBuilder();
        message.append("[accent]地图投票[]\n");
        message.append("当前地图: [white]").append(currentMapName()).append("[]\n");
        message.append("第 [white]").append(currentPage + 1).append("/").append(totalPages).append("[] 页，选择一张地图发起投票，或点击搜索地图。");

        Call.menu(
            player.con,
            MAP_VOTE_SELECT_MENU_BASE_ID + currentPage,
            "[accent]地图投票[]",
            message.toString(),
            buildMapSelectionOptions(player, pageMaps, currentPage, totalPages)
        );
    }

    private String[][] buildMapSelectionOptions(Player player, List<Map> pageMaps, int page, int totalPages){
        List<String[]> rows = new ArrayList<String[]>();

        for(int i = 0; i < pageMaps.size(); i += 2){
            int end = Math.min(i + 2, pageMaps.size());
            String[] row = new String[end - i];
            for(int j = i; j < end; j++){
                row[j - i] = pageMaps.get(j).plainName();
            }
            rows.add(row);
        }

        rows.add(new String[]{"搜索地图"});
        rows.add(new String[]{page > 0 ? "上一页" : "[gray]上一页[]", page + 1 < totalPages ? "下一页" : "[gray]下一页[]"});

        if(player != null && player.admin()){
            rows.add(new String[]{"下一张地图"});
        }

        String link = normalizeUrl(config.mapVote.homeLinkUrl);
        if(link.isEmpty()){
            rows.add(new String[]{"关闭"});
        }else{
            String label = config.mapVote.homeLinkLabel == null ? "" : config.mapVote.homeLinkLabel.trim();
            if(label.isEmpty()){
                label = "WZ资源站";
            }
            rows.add(new String[]{label, "关闭"});
        }

        return rows.toArray(new String[rows.size()][]);
    }

    private void handleMapSelection(Player player, int page, int option){
        List<Map> maps = availableMaps();
        if(maps.isEmpty()){
            player.sendMessage("[scarlet]当前没有可投票的地图。[]");
            return;
        }

        int totalPages = Math.max((maps.size() + MAP_PAGE_SIZE - 1) / MAP_PAGE_SIZE, 1);
        int currentPage = clampPage(page, totalPages);
        int start = currentPage * MAP_PAGE_SIZE;
        int end = Math.min(start + MAP_PAGE_SIZE, maps.size());
        List<Map> pageMaps = maps.subList(start, end);

        if(option < pageMaps.size()){
            startVote(player, pageMaps.get(option));
            return;
        }

        option -= pageMaps.size();
        if(option == 0){
            openMapSearch(player, "");
            return;
        }
        option -= 1;

        if(option == 0 && currentPage > 0){
            showMapVoteMenu(player, currentPage - 1);
            return;
        }
        option -= 1;

        if(option == 0 && currentPage + 1 < totalPages){
            showMapVoteMenu(player, currentPage + 1);
            return;
        }
        option -= 1;

        if(player != null && player.admin()){
            if(option == 0){
                changeToNextMap(player);
                return;
            }
            option -= 1;
        }

        String link = normalizeUrl(config.mapVote.homeLinkUrl);
        if(!link.isEmpty() && option == 0){
            Call.openURI(player.con, link);
        }
    }

    private void changeToNextMap(Player player){
        if(player == null || !player.admin()){
            return;
        }

        Map next = nextMapCandidate();
        if(next == null){
            player.sendMessage("[scarlet]没有可切换的下一张地图。[]");
            return;
        }

        Call.sendMessage("[accent]" + displayPlayerName(player) + "[] 正在切换到下一张地图: [white]" + next.plainName() + "[]");
        loadMap(next);
    }

    private void showMapVoteBrowser(Player player, int page){
        if(player == null || player.con == null){
            return;
        }

        ExternalVoteSession external = getExternalVoteSession();
        if(external != null){
            showExternalVoteMenu(player, external);
            return;
        }

        VoteStatus active = getVoteStatus();
        if(active != null){
            showActiveVoteMenu(player, active);
            return;
        }

        List<Map> maps = availableMaps();
        if(maps.isEmpty()){
            player.sendMessage("[scarlet]当前没有可投票的地图。[]");
            return;
        }

        int totalPages = Math.max((maps.size() + MAP_PAGE_SIZE - 1) / MAP_PAGE_SIZE, 1);
        int currentPage = clampPage(page, totalPages);
        MapBrowserState state = new MapBrowserState(currentPage, totalPages, maps);
        mapBrowserStates.put(player.uuid(), state);

        Call.textInput(
            player.con,
            MAP_VOTE_SEARCH_INPUT_ID,
            "[accent]地图搜索[]",
            buildMapVoteBrowserMessage(player, state),
            64,
            state.pendingInput,
            false
        );
    }

    private String buildMapVoteBrowserMessage(Player player, MapBrowserState state){
        List<Map> pageMaps = state.pageMaps();
        StringBuilder message = new StringBuilder();
        message.append("[accent]地图搜索[]\n");
        message.append("当前地图: [white]").append(currentMapName()).append("[]\n");
        message.append("第 [white]").append(state.page + 1).append("/").append(state.totalPages).append("[] 页\n");
        message.append("输入页内编号、完整地图名，或输入: [white]next prev open close");
        if(player != null && player.admin()){
            message.append(" cycle");
        }
        message.append("[]\n");
        message.append("本页地图:\n");

        for(int i = 0; i < pageMaps.size(); i++){
            message.append("[accent]").append(i + 1).append(".[] [white]").append(pageMaps.get(i).plainName()).append("[]\n");
        }

        String link = normalizeUrl(config.mapVote.homeLinkUrl);
        if(!link.isEmpty()){
            String label = config.mapVote.homeLinkLabel == null ? "" : config.mapVote.homeLinkLabel.trim();
            if(label.isEmpty()){
                label = "资源站";
            }
            message.append("输入 [white]open[] 打开: [white]").append(label).append("[]\n");
        }

        return message.toString().trim();
    }

    private void handleMapVoteTextInput(Player player, String text){
        if(player == null){
            return;
        }

        String input = text == null ? "" : text.trim();
        MapBrowserState state = mapBrowserStates.get(player.uuid());
        if(state == null){
            showMapVoteBrowser(player, 0);
            return;
        }

        state.pendingInput = input;
        if(input.isEmpty()){
            showMapVoteBrowser(player, state.page);
            return;
        }

        String normalized = input.toLowerCase(Locale.ROOT);
        if("close".equals(normalized) || "cancel".equals(normalized)){
            mapBrowserStates.remove(player.uuid());
            player.sendMessage("[accent]已关闭地图搜索。[]");
            return;
        }

        if("next".equals(normalized) || ">".equals(normalized)){
            showMapVoteBrowser(player, state.page + 1);
            return;
        }

        if("prev".equals(normalized) || "previous".equals(normalized) || "<".equals(normalized)){
            showMapVoteBrowser(player, state.page - 1);
            return;
        }

        if("open".equals(normalized) || "link".equals(normalized)){
            String link = normalizeUrl(config.mapVote.homeLinkUrl);
            if(link.isEmpty()){
                player.sendMessage("[scarlet]当前没有配置地图资源站链接。[]");
            }else if(player.con != null){
                Call.openURI(player.con, link);
            }
            showMapVoteBrowser(player, state.page);
            return;
        }

        if(player.admin() && ("cycle".equals(normalized) || "nextmap".equals(normalized))){
            mapBrowserStates.remove(player.uuid());
            changeToNextMap(player);
            return;
        }

        List<Map> pageMaps = state.pageMaps();
        Integer selectedIndex = parsePositiveInt(normalized);
        if(selectedIndex != null){
            int pageIndex = selectedIndex - 1;
            if(pageIndex >= 0 && pageIndex < pageMaps.size()){
                mapBrowserStates.remove(player.uuid());
                startVote(player, pageMaps.get(pageIndex));
                return;
            }

            player.sendMessage("[scarlet]这个编号不在当前页范围内。[]");
            showMapVoteBrowser(player, state.page);
            return;
        }

        Map exact = findMap(input);
        if(exact != null){
            mapBrowserStates.remove(player.uuid());
            startVote(player, exact);
            return;
        }

        player.sendMessage("[scarlet]没有找到地图，请输入页内编号或完整地图名。[]");
        showMapVoteBrowser(player, state.page);
    }

    private Integer parsePositiveInt(String value){
        try{
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        }catch(NumberFormatException ignored){
            return null;
        }
    }

    private boolean isMapSearchShortcut(String[] args){
        if(args == null || args.length == 0){
            return false;
        }

        String first = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
        return "search".equals(first) || "find".equals(first) || "s".equals(first);
    }

    private String extractMapSearchQuery(String[] args){
        if(args == null || args.length <= 1){
            return "";
        }
        return joinArgs(args, 1);
    }

    private String joinArgs(String[] args, int startIndex){
        if(args == null || startIndex >= args.length){
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for(int i = Math.max(startIndex, 0); i < args.length; i++){
            String part = args[i];
            if(part == null || part.trim().isEmpty()){
                continue;
            }
            if(builder.length() > 0){
                builder.append(' ');
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private void openMapSearch(Player player, String query){
        if(player == null){
            return;
        }

        String normalized = query == null ? "" : query.trim();
        if(normalized.isEmpty()){
            showMapVoteBrowser(player, 0);
            return;
        }

        List<Map> matches = searchMaps(normalized);
        if(matches.isEmpty()){
            player.sendMessage("[scarlet]没有匹配到地图:[] " + normalized);
            showMapVoteBrowser(player, 0);
            return;
        }

        if(matches.size() == 1){
            startVote(player, matches.get(0));
            return;
        }

        int totalPages = Math.max((matches.size() + MAP_PAGE_SIZE - 1) / MAP_PAGE_SIZE, 1);
        MapBrowserState state = new MapBrowserState(0, totalPages, matches);
        state.pendingInput = normalized;
        mapBrowserStates.put(player.uuid(), state);

        Call.textInput(
            player.con,
            MAP_VOTE_SEARCH_INPUT_ID,
            "[accent]地图搜索[]",
            buildMapVoteBrowserMessage(player, state),
            64,
            state.pendingInput,
            false
        );
    }

    private void openMapFromQuery(Player player, String query){
        if(player == null){
            return;
        }

        String normalized = query == null ? "" : query.trim();
        if(normalized.isEmpty()){
            showMapVoteBrowser(player, 0);
            return;
        }

        Map exact = findMap(normalized);
        if(exact != null){
            startVote(player, exact);
            return;
        }

        openMapSearch(player, normalized);
    }

    private List<Map> searchMaps(String query){
        List<Map> maps = availableMaps();
        if(query == null || query.trim().isEmpty()){
            return maps;
        }

        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<Map> matches = new ArrayList<Map>();
        for(Map map : maps){
            String plain = map.plainName();
            String name = map.name() == null ? "" : map.name();
            if(plain.toLowerCase(Locale.ROOT).contains(normalized) || name.toLowerCase(Locale.ROOT).contains(normalized)){
                matches.add(map);
            }
        }
        return matches;
    }

    private void startVoteByName(Player player, String mapName){
        Map map = findMap(mapName);
        if(map == null){
            player.sendMessage("[scarlet]地图无效:[] " + mapName);
            return;
        }

        startVote(player, map);
    }

    private void startVote(Player player, Map map){
        if(player == null || map == null){
            return;
        }

        ExternalVoteSession external = getExternalVoteSession();
        if(external != null){
            showExternalVoteMenu(player, external);
            player.sendMessage("[scarlet]Another external vote session is already active.[]");
            return;
        }

        VoteStatus startedStatus = null;
        VoteDecision immediateDecision = VoteDecision.Pending;

        synchronized(voteLock){
            if(activeVote != null){
                VoteStatus status = toStatus(activeVote);
                showActiveVoteMenu(player, status);
                player.sendMessage("[scarlet]已经有换图投票正在进行中。[]");
                return;
            }

            VoteState vote = new VoteState();
            vote.id = ++voteSequence;
            vote.map = map;
            vote.startedBy = displayPlayerName(player);
            vote.expiresAtMillis = System.currentTimeMillis() + config.mapVote.durationSec * 1000L;
            vote.votes.put(playerKey(player), Byte.valueOf((byte)1));

            VoteStatus status = toStatus(vote);
            VoteDecision decision = evaluate(status);

            if(decision == VoteDecision.Pending){
                activeVote = vote;
                startedStatus = status;
                scheduleVoteTasks(vote.id);
            }else{
                startedStatus = status;
                immediateDecision = decision;
            }
        }

        if(immediateDecision != VoteDecision.Pending){
            finishVote(startedStatus, immediateDecision);
            return;
        }

        if(startedStatus != null){
            Call.sendMessage(
                "[accent]" + displayPlayerName(player) + "[] 发起了换图投票: [white]" + startedStatus.mapName +
                "[] ([green]" + startedStatus.yes + "/" + startedStatus.needed +
                "[]，限时 [white]" + config.mapVote.durationSec + "s[]，输入 [white]/vote[] 或 [white]/votemap[] 可投票)"
            );
            broadcastVoteHud();
            showActiveVoteMenuToAll();
        }
    }

    private void castVote(Player player, byte choice){
        ExternalVoteSession external = getExternalVoteSession();
        if(external != null){
            castExternalVote(player, choice, external);
            return;
        }

        VoteStatus current;
        VoteDecision decision;

        synchronized(voteLock){
            if(activeVote == null){
                player.sendMessage("[scarlet]当前没有进行中的换图投票。[]");
                return;
            }

            activeVote.votes.put(playerKey(player), Byte.valueOf(choice));
            current = toStatus(activeVote);
            decision = evaluate(current);

            if(decision != VoteDecision.Pending){
                cancelVoteTasksLocked();
                activeVote = null;
            }
        }

        String label = voteChoiceLabel(choice);
        player.sendMessage("[accent]你选择了 " + label);
        Call.sendMessage("[accent]" + displayPlayerName(player) + "[] 选择了 " + label);

        if(decision == VoteDecision.Pending){
            broadcastVoteHud();
        }else{
            finishVote(current, decision);
        }
    }

    private void showActiveVoteMenu(Player player, VoteStatus status){
        if(player == null || player.con == null){
            return;
        }

        ExternalVoteSession external = getExternalVoteSession();
        if(external != null){
            showExternalVoteMenu(player, external);
            return;
        }

        if(status == null){
            player.sendMessage("[scarlet]当前没有进行中的换图投票。[]");
            return;
        }

        long remainingMs = Math.max(status.expiresAtMillis - System.currentTimeMillis(), 0L);
        StringBuilder message = new StringBuilder();
        message.append("[accent]换图投票[]\n");
        message.append("目标地图: [white]").append(status.mapName).append("[]\n");
        message.append("发起玩家: ").append(status.startedBy).append('\n');
        message.append("同意: [green]").append(status.yes).append("[]  反对: [scarlet]").append(status.no)
            .append("[]  中立: [lightgray]").append(status.neutral).append("[]\n");
        message.append("通过需要 [white]").append(status.needed).append("[]  剩余: [white]")
            .append((int)Math.ceil(remainingMs / 1000f)).append("s[]");

        Call.menu(
            player.con,
            MAP_VOTE_PROMPT_MENU_ID,
            "[accent]换图投票[]",
            message.toString(),
            new String[][]{
                {"同意", "反对", "中立", "关闭"}
            }
        );
    }

    private void showActiveVoteMenuToAll(){
        VoteStatus status = getVoteStatus();
        if(status == null){
            return;
        }

        for(Player player : Groups.player){
            showActiveVoteMenu(player, status);
        }
    }

    private void scheduleVoteTasks(int voteId){
        cancelVoteTasksLocked();

        float timeoutSeconds = Math.max(config.mapVote.durationSec, 1);
        float refreshSeconds = Math.max(config.mapVote.statusRefreshMs / 1000f, 0.5f);

        voteTimeoutTask = Timer.schedule(() -> expireVote(voteId), timeoutSeconds);
        voteHudTask = Timer.schedule(() -> broadcastVoteHud(voteId), 0f, refreshSeconds);
    }

    private void expireVote(int voteId){
        VoteStatus expired = null;

        synchronized(voteLock){
            if(activeVote == null || activeVote.id != voteId){
                return;
            }

            expired = toStatus(activeVote);
            cancelVoteTasksLocked();
            activeVote = null;
        }

        finishVote(expired, VoteDecision.Expired);
    }

    private void finishVote(VoteStatus status, VoteDecision decision){
        if(status == null){
            return;
        }

        Call.hideHudText();

        switch(decision){
            case Passed:
                Call.sendMessage(
                    "[accent]换图投票通过[]: [white]" + status.mapName + "[] ([green]" + status.yes +
                    "/" + status.needed + "[] 反对 [scarlet]" + status.no + "[] 中立 [lightgray]" + status.neutral + "[])"
                );
                loadMap(status.map);
                return;
            case Rejected:
                Call.sendMessage(
                    "[scarlet]换图投票未通过[]: [white]" + status.mapName +
                    "[] (同意 [green]" + status.yes + "[] / 反对 [scarlet]" + status.no +
                    "[] / 中立 [lightgray]" + status.neutral + "[] / 需要 " + status.needed + ")"
                );
                return;
            case Expired:
                Call.sendMessage(
                    "[scarlet]换图投票超时[]: [white]" + status.mapName +
                    "[] (同意 [green]" + status.yes + "[] / 反对 [scarlet]" + status.no +
                    "[] / 中立 [lightgray]" + status.neutral + "[])"
                );
                return;
            default:
        }
    }

    private void loadMap(Map map){
        if(map == null){
            return;
        }

        clearVoteState();

        WorldReloader reloader = new WorldReloader();
        try{
            reloader.begin();
            mindustry.game.Gamemode mode = Vars.state != null && Vars.state.rules != null ? Vars.state.rules.mode() : mindustry.game.Gamemode.survival;
            mindustry.game.Rules rules = map.applyRules(mode);
            Vars.world.loadMap(map, rules);
            Vars.state.rules = rules;
            Vars.logic.play();
            reloader.end();
        }catch(Throwable t){
            Log.err("Failed to load map vote target: " + map.plainName());
            Log.err(t);
            Call.sendMessage("[scarlet]换图失败:[] " + Strings.getSimpleMessage(t));
        }
    }

    private void broadcastVoteHud(){
        VoteStatus status = getVoteStatus();
        if(status == null){
            Call.hideHudText();
            return;
        }

        Call.setHudTextReliable(buildVoteHud(status));
    }

    private void broadcastVoteHud(int voteId){
        synchronized(voteLock){
            if(activeVote == null || activeVote.id != voteId){
                return;
            }
        }

        broadcastVoteHud();
    }

    private String buildVoteHud(VoteStatus status){
        long remainingMs = Math.max(status.expiresAtMillis - System.currentTimeMillis(), 0L);
        StringBuilder builder = new StringBuilder();
        builder.append("[accent]换图投票[]\n");
        builder.append("地图: [white]").append(status.mapName).append("[]\n");
        builder.append("发起: ").append(status.startedBy).append('\n');
        builder.append("同意: [green]").append(status.yes).append("[]/[white]").append(status.needed)
            .append("[]  反对: [scarlet]").append(status.no).append("[]  中立: [lightgray]").append(status.neutral).append("[]\n");
        builder.append("剩余: [white]").append(Strings.fixed(remainingMs / 1000f, 1)).append("s[]");
        return builder.toString();
    }

    private VoteStatus getVoteStatus(){
        synchronized(voteLock){
            return activeVote == null ? null : toStatus(activeVote);
        }
    }

    private VoteStatus toStatus(VoteState vote){
        VoteStatus status = new VoteStatus();
        status.id = vote.id;
        status.map = vote.map;
        status.mapName = vote.map == null ? "<unknown>" : vote.map.plainName();
        status.startedBy = vote.startedBy == null ? "玩家" : vote.startedBy;
        status.expiresAtMillis = vote.expiresAtMillis;

        for(Entry<String, Byte> entry : vote.votes.entrySet()){
            byte value = entry.getValue().byteValue();
            if(value > 0){
                status.yes++;
            }else if(value < 0){
                status.no++;
            }else{
                status.neutral++;
            }
        }

        status.needed = neededVotes();
        return status;
    }

    private VoteDecision evaluate(VoteStatus status){
        int voted = status.yes + status.no + status.neutral;
        if(status.yes >= status.needed){
            return VoteDecision.Passed;
        }
        if(status.no >= status.needed){
            return VoteDecision.Rejected;
        }
        if(voted >= Math.max(realPlayerCount(), 1)){
            return VoteDecision.Rejected;
        }
        return VoteDecision.Pending;
    }

    private int neededVotes(){
        int totalPlayers = Math.max(realPlayerCount(), 1);
        return totalPlayers / 2 + 1;
    }

    private void clearVoteState(){
        synchronized(voteLock){
            cancelVoteTasksLocked();
            activeVote = null;
        }
        Call.hideHudText();
    }

    private void cancelVoteTasksLocked(){
        if(voteTimeoutTask != null){
            voteTimeoutTask.cancel();
            voteTimeoutTask = null;
        }
        if(voteHudTask != null){
            voteHudTask.cancel();
            voteHudTask = null;
        }
    }

    private void broadcastStatusBar(){
        if(config == null || !config.statusBar.enabled || realPlayerCount() <= 0){
            return;
        }

        double cpuPercent = cpuTracker.sample();
        double memoryMb = currentProcessMemoryMb();

        for(Player player : Groups.player){
            showStatusBarTo(player, cpuPercent, memoryMb);
        }
    }

    private void showStatusBarTo(Player player, double cpuPercent, double memoryMb){
        if(player == null || player.con == null){
            return;
        }

        String message = renderStatusBarMessage(player, cpuPercent, memoryMb);
        if(message.trim().isEmpty()){
            return;
        }

        float duration = Math.max(config.statusBar.popupDurationMs, 800) / 1000f;
        int align = alignValue(config.statusBar.align);
        String popupId = config.statusBar.popupId == null ? "" : config.statusBar.popupId.trim();

        if(popupId.isEmpty()){
            Call.infoPopup(player.con, message, duration, align, config.statusBar.top, config.statusBar.left, config.statusBar.bottom, config.statusBar.right);
        }else{
            Call.infoPopup(player.con, message, popupId, duration, align, config.statusBar.top, config.statusBar.left, config.statusBar.bottom, config.statusBar.right);
        }
    }

    private String renderStatusBarMessage(Player player, double cpuPercent, double memoryMb){
        PluginConfig.StatusBar status = config.statusBar;
        StringBuilder builder = new StringBuilder();

        if(status.headerEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.headerText, player, cpuPercent, memoryMb));
        }
        if(status.serverNameEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.serverNameFormat, player, cpuPercent, memoryMb));
        }
        if(status.performanceEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.performanceFormat, player, cpuPercent, memoryMb));
        }
        if(status.currentMapEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.currentMapFormat, player, cpuPercent, memoryMb));
        }
        if(status.gameTimeEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.gameTimeFormat, player, cpuPercent, memoryMb));
        }
        if(status.playerCountEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.playerCountFormat, player, cpuPercent, memoryMb));
        }
        if(status.welcomeEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.welcomeFormat, player, cpuPercent, memoryMb));
        }
        if(status.qqGroupEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.qqGroupFormat, player, cpuPercent, memoryMb));
        }
        if(status.customMessageEnabled){
            appendIfNotBlank(builder, applyStatusBarPlaceholders(status.customMessageFormat, player, cpuPercent, memoryMb));
        }

        return builder.toString();
    }

    private void appendIfNotBlank(StringBuilder builder, String line){
        if(line == null || line.trim().isEmpty()){
            return;
        }

        if(builder.length() > 0){
            builder.append('\n');
        }
        builder.append(line);
    }

    private String applyStatusBarPlaceholders(String template, Player player, double cpuPercent, double memoryMb){
        String value = template == null ? "" : template.trim();
        if(value.isEmpty()){
            return "";
        }

        PlayerStatusSnapshot status = snapshotPlayerStatus(player);
        return value
            .replace("{server_name}", currentServerName())
            .replace("{cpu_percent}", formatStatusBarFloat(cpuPercent))
            .replace("{memory_mb}", formatStatusBarFloat(memoryMb))
            .replace("{players}", Integer.toString(displayPlayerCount()))
            .replace("{current_map}", currentMapName())
            .replace("{game_time}", currentGameTime())
            .replace("{player_name}", currentPlayerName(player))
            .replace("{bind_state}", status.bound ? "true" : "false")
            .replace("{bind_status_text}", status.bound ? "已绑定" : "未绑定")
            .replace("{qq_number}", status.qqNumber)
            .replace("{permission_group}", status.permissionGroupDisplay)
            .replace("{permission_group_id}", status.permissionGroupId)
            .replace("{qq_group}", config.statusBar.qqGroupText == null ? "" : config.statusBar.qqGroupText.trim())
            .replace("{message}", config.statusBar.customMessageText == null ? "" : config.statusBar.customMessageText.trim())
            .replace("{uptime}", formatStatusBarDuration((System.currentTimeMillis() - pluginStartMillis) / 1000L));
    }

    private String buildHelpStatusSummary(Player player){
        if(player == null){
            return "";
        }

        PlayerStatusSnapshot status = snapshotPlayerStatus(player);
        String bindText = status.bound ? "已绑定" : "未绑定";
        String groupText = status.permissionGroupDisplay == null || status.permissionGroupDisplay.trim().isEmpty()
            ? (status.permissionGroupId == null ? "" : status.permissionGroupId.trim())
            : status.permissionGroupDisplay.trim();
        if(groupText.isEmpty()){
            groupText = "未分组";
        }

        StringBuilder builder = new StringBuilder("[accent]当前状态[] ");
        builder.append("[white]绑定: ").append(bindText).append("[]");
        builder.append(" [lightgray]|[] [white]权限组: ").append(groupText).append("[]");
        if(status.qqNumber != null && !status.qqNumber.trim().isEmpty()){
            builder.append(" [lightgray]|[] [white]QQ: ").append(status.qqNumber.trim()).append("[]");
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private PlayerStatusSnapshot snapshotPlayerStatus(Player player){
        PlayerStatusSnapshot snapshot = new PlayerStatusSnapshot();
        if(player == null){
            return snapshot;
        }

        try{
            String uuid = resolvePlayerUuid(player);
            String comId = resolvePlayerComId(uuid);
            snapshot.permissionGroupId = resolvePermissionGroupId(player);
            snapshot.permissionGroupDisplay = resolvePermissionGroupDisplay(player, snapshot.permissionGroupId);

            Object listData = getSharedService("mdt.listdata.api");
            if(listData == null){
                listData = getSharedService("com.mdt.listdata.api.ListDataSystemApi");
            }
            if(listData == null){
                return snapshot;
            }

            java.lang.reflect.Method getObject = listData.getClass().getMethod("getObject", String.class, String.class);
            java.util.Map<String, String> bindObject = null;
            java.util.Map<String, String> profileObject = null;

            if(comId != null && !comId.trim().isEmpty()){
                Object bindCurrent = getObject.invoke(listData, "player_bind", comId);
                if(bindCurrent instanceof java.util.Map){
                    bindObject = new HashMap<String, String>((java.util.Map<String, String>)bindCurrent);
                }
                Object profileCurrent = getObject.invoke(listData, "player_profile", comId);
                if(profileCurrent instanceof java.util.Map){
                    profileObject = new HashMap<String, String>((java.util.Map<String, String>)profileCurrent);
                }
            }

            if((profileObject == null || profileObject.isEmpty()) && uuid != null && !uuid.trim().isEmpty()){
                Object profileByUuid = getObject.invoke(listData, "player_profile", uuid);
                if(profileByUuid instanceof java.util.Map){
                    profileObject = new HashMap<String, String>((java.util.Map<String, String>)profileByUuid);
                }
            }

            snapshot.qqNumber = firstNonBlank(
                readValue(bindObject, "qqNumber", "qq", "qq_id"),
                readValue(profileObject, "qqNumber", "qq", "qq_id"),
                ""
            );

            String boundRaw = firstNonBlank(
                readValue(bindObject, "bound", "isBound"),
                readValue(profileObject, "bound", "isBound"),
                ""
            );
            snapshot.bound = "true".equalsIgnoreCase(boundRaw) || !snapshot.qqNumber.isEmpty();
        }catch(Throwable ignored){
            // Ignore status placeholder lookup failures and keep graceful fallbacks.
        }

        return snapshot;
    }

    private String resolvePermissionGroupId(Player player){
        try{
            Class<?> registryClass = Class.forName("com.mdt.permission.PermissionGroupRegistry");
            java.lang.reflect.Method get = registryClass.getMethod("get");
            Object service = get.invoke(null);
            if(service == null){
                return player != null && player.admin() ? "root" : "";
            }
            java.lang.reflect.Method method = service.getClass().getMethod("getResolvedGroupId", Player.class);
            Object value = method.invoke(service, player);
            return value == null ? "" : value.toString().trim();
        }catch(Throwable ignored){
            return player != null && player.admin() ? "root" : "";
        }
    }

    private String resolvePermissionGroupDisplay(Player player, String fallbackId){
        try{
            Class<?> registryClass = Class.forName("com.mdt.permission.PermissionGroupRegistry");
            java.lang.reflect.Method get = registryClass.getMethod("get");
            Object service = get.invoke(null);
            if(service == null){
                return fallbackPermissionDisplay(player, fallbackId);
            }
            java.lang.reflect.Method method = service.getClass().getMethod("getResolvedGroupDisplayName", Player.class);
            Object value = method.invoke(service, player);
            String resolved = value == null ? "" : value.toString().trim();
            return resolved.isEmpty() ? fallbackPermissionDisplay(player, fallbackId) : resolved;
        }catch(Throwable ignored){
            return fallbackPermissionDisplay(player, fallbackId);
        }
    }

    private String fallbackPermissionDisplay(Player player, String fallbackId){
        if(fallbackId != null && !fallbackId.trim().isEmpty()){
            return fallbackId.trim();
        }
        if(player != null && player.admin()){
            return "??";
        }
        return "";
    }

    private String readValue(java.util.Map<String, String> values, String... keys){
        if(values == null || values.isEmpty() || keys == null){
            return "";
        }
        for(String key : keys){
            if(key == null){
                continue;
            }
            String current = values.get(key);
            if(current != null && !current.trim().isEmpty()){
                return current.trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String... values){
        if(values == null){
            return "";
        }
        for(String current : values){
            if(current != null && !current.trim().isEmpty()){
                return current.trim();
            }
        }
        return "";
    }

    private int realPlayerCount(){
        return Groups.player.size();
    }

    private int displayPlayerCount(){
        return realPlayerCount() + Math.max(config == null ? 0 : config.virtualPlayers, 0);
    }

    private String currentServerName(){
        String name = Administration.Config.serverName.string();
        if(name == null || name.trim().isEmpty()){
            return "mdt-server";
        }
        return name.trim();
    }

    private String currentMapName(){
        if(Vars.state == null || Vars.state.map == null){
            return "unknown";
        }

        String name = Vars.state.map.plainName();
        return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
    }

    private String currentPlayerName(Player player){
        String name = displayPlayerName(player);
        return name.trim().isEmpty() ? "玩家" : name.trim();
    }

    private String currentGameTime(){
        if(Vars.state == null){
            return formatStatusBarDuration((System.currentTimeMillis() - pluginStartMillis) / 1000L);
        }

        long seconds = Math.max(0L, Math.round(Vars.state.tick / 60.0));
        return formatStatusBarDuration(seconds);
    }

    private String formatStatusBarDuration(long totalSeconds){
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if(hours > 0){
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private String formatStatusBarFloat(double value){
        if(value < 0){
            value = 0;
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private double currentProcessMemoryMb(){
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024d / 1024d;
    }

    private List<Map> availableMaps(){
        List<Map> result = new ArrayList<Map>();
        for(Map map : Vars.maps.all()){
            if(map != null){
                result.add(map);
            }
        }

        Collections.sort(result, new Comparator<Map>(){
            @Override
            public int compare(Map left, Map right){
                return normalizeMapName(left.plainName()).compareTo(normalizeMapName(right.plainName()));
            }
        });

        return result;
    }

    private Map findMap(String rawName){
        String normalized = normalizeMapName(rawName);
        if(normalized.isEmpty()){
            return null;
        }

        Map fuzzy = null;
        for(Map map : availableMaps()){
            String plain = normalizeMapName(map.plainName());
            String named = normalizeMapName(map.name());

            if(plain.equals(normalized) || named.equals(normalized)){
                return map;
            }

            if(fuzzy == null && (plain.contains(normalized) || named.contains(normalized))){
                fuzzy = map;
            }
        }

        return fuzzy;
    }

    private Map nextMapCandidate(){
        List<Map> maps = availableMaps();
        if(maps.isEmpty()){
            return null;
        }

        String current = normalizeMapName(currentMapName());
        for(int i = 0; i < maps.size(); i++){
            if(normalizeMapName(maps.get(i).plainName()).equals(current) || normalizeMapName(maps.get(i).name()).equals(current)){
                return maps.get((i + 1) % maps.size());
            }
        }

        return maps.get(0);
    }

    private String normalizeMapName(String name){
        if(name == null){
            return "";
        }

        return Strings.stripColors(name)
            .replace('_', ' ')
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    private String joinArgs(String[] args){
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < args.length; i++){
            if(i > 0){
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString().trim();
    }

    private String displayPlayerName(Player player){
        if(player == null){
            return "玩家";
        }

        String name = player.name();
        if(name != null && !name.trim().isEmpty()){
            return name.trim();
        }

        name = player.plainName();
        if(name != null && !name.trim().isEmpty()){
            return name.trim();
        }

        return "玩家";
    }

    private String playerKey(Player player){
        if(player == null){
            return "unknown";
        }

        String uuid = player.uuid();
        if(uuid != null && !uuid.trim().isEmpty()){
            return uuid.trim().toLowerCase(Locale.ROOT);
        }

        return normalizeMapName(player.plainName());
    }

    private String normalizeUrl(String raw){
        String value = raw == null ? "" : raw.trim();
        if(value.isEmpty()){
            return "";
        }
        if(value.contains("://")){
            return value;
        }
        return "https://" + value;
    }

    private void reloadExternalHelpRegistry(){
        List<HelpCommandButton> loaded = new ArrayList<HelpCommandButton>();
        String status = "disabled";

        try{
            if(config != null && config.externalCommandRegistry != null && config.externalCommandRegistry.enabled){
                Fi root = new Fi(config.externalCommandRegistry.pluginsRootPath);
                if(!root.exists() || !root.isDirectory()){
                    status = "missing-root";
                }else{
                    Set<String> seen = new LinkedHashSet<String>();
                    int pluginCount = 0;
                    for(Fi pluginDir : root.list()){
                        if(pluginDir == null || !pluginDir.isDirectory()){
                            continue;
                        }

                        Fi srcRoot = pluginDir.child("src").child("main").child("java");
                        if(!srcRoot.exists() || !srcRoot.isDirectory()){
                            continue;
                        }

                        pluginCount++;
                        String pluginName = loadPluginDisplayName(pluginDir);
                        collectExternalClientCommands(srcRoot, pluginName, loaded, seen);
                    }
                    status = "ok plugins=" + pluginCount + " commands=" + loaded.size();
                }
            }
        }catch(Throwable t){
            Log.err("Failed to reload external help registry.");
            Log.err(t);
            loaded.clear();
            status = "error";
        }

        synchronized(externalHelpButtons){
            externalHelpButtons.clear();
            externalHelpButtons.addAll(loaded);
        }
        externalCommandRegistryStatus = status;
    }

    private String loadPluginDisplayName(Fi pluginDir){
        String fallback = pluginDir == null ? "plugin" : pluginDir.name();
        if(pluginDir == null){
            return fallback;
        }

        Fi pluginJson = pluginDir.child("plugin.json");
        if(!pluginJson.exists()){
            return fallback;
        }

        try{
            Matcher matcher = JSON_NAME_PATTERN.matcher(pluginJson.readString("UTF-8"));
            if(matcher.find()){
                String value = unescapeJavaString(matcher.group(1)).trim();
                if(!value.isEmpty()){
                    return value;
                }
            }
        }catch(Throwable ignored){
        }
        return fallback;
    }

    private void collectExternalClientCommands(Fi srcRoot, String pluginName, List<HelpCommandButton> output, Set<String> seen){
        for(Fi file : srcRoot.findAll(value -> value != null && "java".equalsIgnoreCase(value.extension()))){
            try{
                String raw = file.readString("UTF-8");
                Matcher usageMatcher = REGISTER_WITH_USAGE_PATTERN.matcher(raw);
                while(usageMatcher.find()){
                    String command = unescapeJavaString(usageMatcher.group(1)).trim();
                    if(command.isEmpty() || !seen.add(command.toLowerCase(Locale.ROOT))){
                        continue;
                    }
                    output.add(createExternalHelpButton(
                        command,
                        unescapeJavaString(usageMatcher.group(2)).trim(),
                        unescapeJavaString(usageMatcher.group(3)).trim(),
                        pluginName
                    ));
                }

                Matcher noUsageMatcher = REGISTER_NO_USAGE_PATTERN.matcher(raw);
                while(noUsageMatcher.find()){
                    String command = unescapeJavaString(noUsageMatcher.group(1)).trim();
                    if(command.isEmpty() || !seen.add(command.toLowerCase(Locale.ROOT))){
                        continue;
                    }
                    output.add(createExternalHelpButton(
                        command,
                        "",
                        unescapeJavaString(noUsageMatcher.group(2)).trim(),
                        pluginName
                    ));
                }
            }catch(Throwable ignored){
            }
        }
    }

    private String unescapeJavaString(String raw){
        if(raw == null || raw.isEmpty()){
            return "";
        }

        StringBuilder builder = new StringBuilder(raw.length());
        for(int index = 0; index < raw.length(); index++){
            char current = raw.charAt(index);
            if(current != '\\' || index + 1 >= raw.length()){
                builder.append(current);
                continue;
            }

            char next = raw.charAt(++index);
            switch(next){
                case 'n':
                    builder.append('\n');
                    break;
                case 'r':
                    builder.append('\r');
                    break;
                case 't':
                    builder.append('\t');
                    break;
                case '"':
                    builder.append('"');
                    break;
                case '\\':
                    builder.append('\\');
                    break;
                default:
                    builder.append(next);
                    break;
            }
        }
        return builder.toString();
    }

    private ExternalVoteSession getExternalVoteSession(){
        try{
            Class<?> pluginClass = Class.forName("com.mdt.vote.VoteDependencyPlugin");
            Object api = pluginClass.getMethod("getApi").invoke(null);
            if(api == null){
                return null;
            }

            Object snapshot = api.getClass().getMethod("getActiveSession").invoke(api);
            if(snapshot == null){
                return null;
            }

            ExternalVoteSession session = new ExternalVoteSession();
            session.api = api;
            session.sessionId = stringValue(snapshot.getClass().getMethod("getSessionId").invoke(snapshot));
            session.title = stringValue(snapshot.getClass().getMethod("getTitle").invoke(snapshot));
            session.description = stringValue(snapshot.getClass().getMethod("getDescription").invoke(snapshot));
            session.yesCount = intValue(snapshot.getClass().getMethod("getYesCount").invoke(snapshot));
            session.noCount = intValue(snapshot.getClass().getMethod("getNoCount").invoke(snapshot));
            session.requiredYesCount = intValue(snapshot.getClass().getMethod("getRequiredYesCount").invoke(snapshot));
            session.remainingSeconds = longValue(snapshot.getClass().getMethod("getRemainingSeconds").invoke(snapshot));
            return session;
        }catch(Throwable ignored){
            return null;
        }
    }

    private void castExternalVote(Player player, byte choice, ExternalVoteSession session){
        if(player == null || session == null || session.api == null){
            return;
        }
        if(choice == 0){
            player.sendMessage("[scarlet]External vote does not support neutral vote.[]");
            return;
        }

        try{
            Object result = session.api.getClass().getMethod(
                "castVote",
                String.class,
                String.class,
                String.class,
                boolean.class
            ).invoke(session.api, session.sessionId, player.uuid(), player.plainName(), Boolean.valueOf(choice > 0));
            boolean success = booleanValue(result.getClass().getMethod("isSuccess").invoke(result));
            String message = stringValue(result.getClass().getMethod("getMessage").invoke(result));
            player.sendMessage((success ? "[accent]" : "[scarlet]") + message + "[]");
        }catch(Throwable t){
            player.sendMessage("[scarlet]External vote bridge failed.[]");
            Log.err(t);
        }
    }

    private void showExternalVoteMenu(Player player, ExternalVoteSession session){
        if(player == null || player.con == null || session == null){
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("[accent]").append(session.title).append("[]\n");
        if(!session.description.isEmpty()){
            message.append(session.description).append("\n");
        }
        message.append("[green]YES[] ").append(session.yesCount)
            .append(" [scarlet]NO[] ").append(session.noCount)
            .append(" [lightgray]Need[] ").append(session.requiredYesCount)
            .append(" [lightgray]Remain[] ").append(session.remainingSeconds).append("s");

        Call.menu(
            player.con,
            MAP_VOTE_PROMPT_MENU_ID,
            "[accent]Vote[]",
            message.toString(),
            new String[][]{
                {"YES", "NO", "Close"}
            }
        );
    }

    private String stringValue(Object value){
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value){
        return value instanceof Number ? ((Number)value).intValue() : 0;
    }

    private long longValue(Object value){
        return value instanceof Number ? ((Number)value).longValue() : 0L;
    }

    private boolean booleanValue(Object value){
        return Boolean.TRUE.equals(value);
    }

    private String voteChoiceLabel(byte choice){
        if(choice > 0){
            return "[green]同意[]";
        }
        if(choice < 0){
            return "[scarlet]反对[]";
        }
        return "[lightgray]中立[]";
    }

    private byte parseVoteKeyword(String raw){
        if(raw == null){
            return Byte.MIN_VALUE;
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if(value.equals("y") || value.equals("yes") || value.equals("agree") || value.equals("1") || value.equals("同意")){
            return (byte)1;
        }
        if(value.equals("n") || value.equals("no") || value.equals("deny") || value.equals("-1") || value.equals("反对")){
            return (byte)-1;
        }
        if(value.equals("u") || value.equals("neutral") || value.equals("skip") || value.equals("0") || value.equals("中立")){
            return (byte)0;
        }
        return Byte.MIN_VALUE;
    }

    private int clampPage(int page, int totalPages){
        if(totalPages <= 1){
            return 0;
        }
        if(page < 0){
            return 0;
        }
        if(page >= totalPages){
            return totalPages - 1;
        }
        return page;
    }

    private int alignValue(String raw){
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if(value.equals("top")){
            return 3;
        }
        if(value.equals("top_right") || value.equals("topright")){
            return 18;
        }
        if(value.equals("left")){
            return 9;
        }
        if(value.equals("center")){
            return 1;
        }
        if(value.equals("right")){
            return 17;
        }
        if(value.equals("bottom_left") || value.equals("bottomleft")){
            return 12;
        }
        if(value.equals("bottom")){
            return 5;
        }
        if(value.equals("bottom_right") || value.equals("bottomright")){
            return 20;
        }
        return 10;
    }

    private enum VoteDecision{
        Pending,
        Passed,
        Rejected,
        Expired
    }

    private static class VoteState{
        int id;
        Map map;
        String startedBy;
        long expiresAtMillis;
        java.util.Map<String, Byte> votes = new HashMap<String, Byte>();
    }

    private static class VoteStatus{
        int id;
        Map map;
        String mapName;
        String startedBy;
        int yes;
        int no;
        int neutral;
        int needed;
        long expiresAtMillis;
    }

    private static class HelpPage{
        final String title;
        final String message;
        final List<HelpCommandButton> buttons;

        HelpPage(String title, String message, List<HelpCommandButton> buttons){
            this.title = title;
            this.message = message;
            this.buttons = buttons;
        }
    }

    private static class MapBrowserState{
        final int page;
        final int totalPages;
        final List<Map> maps;
        String pendingInput = "";

        MapBrowserState(int page, int totalPages, List<Map> maps){
            this.page = page;
            this.totalPages = totalPages;
            this.maps = new ArrayList<Map>(maps);
        }

        List<Map> pageMaps(){
            int start = Math.max(page, 0) * MAP_PAGE_SIZE;
            int end = Math.min(start + MAP_PAGE_SIZE, maps.size());
            if(start >= end){
                return Collections.emptyList();
            }
            return maps.subList(start, end);
        }
    }

    private static class PlayerStatusSnapshot{
        boolean bound;
        String qqNumber = "";
        String permissionGroupId = "";
        String permissionGroupDisplay = "";
    }

    private static class HelpCommandButton{
        final String label;
        final String command;
        final String args;
        final String description;
        final String source;
        final String runText;
        final String usageMessage;
        final HelpButtonAction action;

        HelpCommandButton(String label, String runText, String usageMessage, HelpButtonAction action){
            this(label, extractCommand(runText), "", "", "", runText, usageMessage, action);
        }

        HelpCommandButton(String label, String command, String args, String description, String source, String runText, String usageMessage, HelpButtonAction action){
            this.label = label;
            this.command = command == null ? "" : command;
            this.args = args == null ? "" : args;
            this.description = description == null ? "" : description;
            this.source = source == null ? "" : source;
            this.runText = runText;
            this.usageMessage = usageMessage;
            this.action = action;
        }

        private static String extractCommand(String runText){
            if(runText == null){
                return "";
            }
            String value = runText.trim();
            if(value.startsWith("/")){
                value = value.substring(1);
            }
            int split = value.indexOf(' ');
            return split >= 0 ? value.substring(0, split) : value;
        }
    }

    private static class ExternalVoteSession{
        Object api;
        String sessionId;
        String title;
        String description;
        int yesCount;
        int noCount;
        int requiredYesCount;
        long remainingSeconds;
    }

    private interface HelpButtonAction{
        void run(Player player);
    }

    private static class ProcessCpuTracker{
        private final OperatingSystemMXBean bean;
        private final int processors;
        private long lastSampleTime;
        private long lastCpuTime;
        private double lastPercent;

        ProcessCpuTracker(){
            OperatingSystemMXBean value = null;
            try{
                value = (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            }catch(Throwable ignored){
            }
            bean = value;
            processors = Math.max(Runtime.getRuntime().availableProcessors(), 1);
            lastSampleTime = 0L;
            lastCpuTime = 0L;
            lastPercent = 0d;
        }

        double sample(){
            if(bean == null){
                return 0d;
            }

            long nowTime = System.nanoTime();
            long nowCpu = bean.getProcessCpuTime();

            if(lastSampleTime > 0L && lastCpuTime > 0L && nowTime > lastSampleTime && nowCpu >= lastCpuTime){
                double percent = ((nowCpu - lastCpuTime) * 100d) / ((nowTime - lastSampleTime) * processors);
                if(!Double.isNaN(percent) && !Double.isInfinite(percent) && percent >= 0d){
                    lastPercent = percent;
                }
            }

            lastSampleTime = nowTime;
            lastCpuTime = nowCpu;

            if(lastPercent <= 0d){
                double fallback = bean.getProcessCpuLoad();
                if(fallback >= 0d && !Double.isNaN(fallback)){
                    lastPercent = fallback * 100d;
                }
            }

            if(lastPercent < 0d){
                lastPercent = 0d;
            }
            if(lastPercent > 100d){
                lastPercent = 100d;
            }
            return lastPercent;
        }
    }
}
