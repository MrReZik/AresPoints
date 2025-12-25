package com.mrrezik.aresPoints;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AresPoints extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private PlayerPointsAPI playerPointsAPI;
    private final Set<UUID> activePlayers = new HashSet<>();
    private File dataFile;
    private YamlConfiguration dataConfig;

    // Паттерн для поиска hex цветов вида &#RRGGBB
    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");

    @Override
    public void onEnable() {
        // 1. Загрузка конфига
        saveDefaultConfig();

        // 2. Проверка зависимости PlayerPoints
        if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            this.playerPointsAPI = PlayerPoints.getInstance().getAPI();
            getLogger().info("PlayerPoints подключен успешно.");
        } else {
            getLogger().severe("PlayerPoints не найден! Плагин выключается.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Загрузка базы данных игроков
        loadData();

        // 4. Регистрация событий и команд
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("arespoints") != null) {
            Objects.requireNonNull(getCommand("arespoints")).setExecutor(this);
            Objects.requireNonNull(getCommand("arespoints")).setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        saveData();
    }

    /* --- Событие Убийства --- */
    @EventHandler
    public void onKill(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Проверки: Убийца существует, это не суицид
        if (killer == null || killer.equals(victim)) return;

        // Проверяем, включен ли режим фарма для убийцы
        if (activePlayers.contains(killer.getUniqueId())) {
            int min = getConfig().getInt("settings.min-reward", 1);
            int max = getConfig().getInt("settings.max-reward", 10);

            // Защита от дурака, если в конфиге min > max
            if (min > max) {
                int temp = min; min = max; max = temp;
            }

            int amount = ThreadLocalRandom.current().nextInt(min, max + 1);

            // Выдача валюты
            playerPointsAPI.give(killer.getUniqueId(), amount);

            // Отправка сообщения
            String rawMsg = getConfig().getString("messages.reward-received", "");
            if (!rawMsg.isEmpty()) {
                rawMsg = rawMsg
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%victim%", victim.getName());
                sendMessage(killer, rawMsg);
            }
        }
    }

    /* --- Обработка Команд --- */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("arespoints.admin")) {
            sendMessage(sender, getConfig().getString("messages.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendMessage(sender, getConfig().getString("messages.usage"));
            return true;
        }

        String subCommand = args[0].toLowerCase();

        // Перезагрузка
        if (subCommand.equals("reload")) {
            reloadConfig();
            sendMessage(sender, getConfig().getString("messages.reload"));
            return true;
        }

        // Для команд setplayer и remplayer нужен ник
        if (args.length < 2) {
            sendMessage(sender, getConfig().getString("messages.usage"));
            return true;
        }

        String targetName = args[1];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        // Используем OfflinePlayer, чтобы можно было добавить игрока, которого нет на сервере

        if (subCommand.equals("setplayer")) {
            activePlayers.add(target.getUniqueId());
            saveData();
            sendMessage(sender, getConfig().getString("messages.player-added")
                    .replace("%player%", targetName));
            return true;
        }

        if (subCommand.equals("remplayer")) {
            if (activePlayers.contains(target.getUniqueId())) {
                activePlayers.remove(target.getUniqueId());
                saveData();
                sendMessage(sender, getConfig().getString("messages.player-removed")
                        .replace("%player%", targetName));
            } else {
                sendMessage(sender, getConfig().getString("messages.player-not-in-list")
                        .replace("%player%", targetName));
            }
            return true;
        }

        sendMessage(sender, getConfig().getString("messages.usage"));
        return true;
    }

    /* --- Tab Completer --- */
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("setplayer", "remplayer", "reload");
        }
        return null; // Дефолтный список игроков
    }

    /* --- Утилиты --- */

    // Метод для отправки сообщения с поддержкой &#rrggbb и & цветов
    private void sendMessage(CommandSender sender, String text) {
        if (text == null || text.isEmpty()) return;
        String prefix = getConfig().getString("messages.prefix", "");

        // Склеиваем префикс и текст, затем красим
        Component msg = colorize(prefix + text);
        sender.sendMessage(msg);
    }

    // Логика обработки цветов &#RRGGBB
    private Component colorize(String message) {
        // 1. Обработка hex: &#123456 -> &x&1&2&3&4&5&6 (формат Bungee/Spigot, который понимает Adventure Legacy)
        Matcher matcher = hexPattern.matcher(message);
        StringBuilder buffer = new StringBuilder();

        while (matcher.find()) {
            String hexCode = matcher.group(1);
            StringBuilder replacement = new StringBuilder("&x");
            for (char c : hexCode.toCharArray()) {
                replacement.append("&").append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);

        // 2. Превращаем всё (&a, &x...) в Component
        return LegacyComponentSerializer.legacyAmpersand().deserialize(buffer.toString());
    }

    /* --- Сохранение данных (data.yml) --- */
    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Не удалось создать data.yml");
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        activePlayers.clear();
        List<String> list = dataConfig.getStringList("players");
        for (String s : list) {
            try {
                activePlayers.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveData() {
        if (dataFile == null || dataConfig == null) return;
        List<String> list = activePlayers.stream().map(UUID::toString).collect(Collectors.toList());
        dataConfig.set("players", list);
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Ошибка сохранения data.yml!");
        }
    }
}