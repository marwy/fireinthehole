package org.marwy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.TextParserUtils;
import eu.pb4.placeholders.api.PlaceholderContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

public class Fireinthehole implements ModInitializer {
    public static final String MOD_ID = "fireinthehole";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static final Map<UUID, PlayerFireData> playerData = new HashMap<>();
    private static long SESSION_DURATION = 15 * 60 * 1000; // 15 минут
    private static File saveFile;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Метод для обновления таба для всех игроков
    private void updatePlayerListForAll(ServerPlayerEntity player) {
        // Создаем пакет обновления таба
        PlayerListS2CPacket packet = new PlayerListS2CPacket(PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME, player);
        
        // Отправляем пакет всем игрокам
        player.getServer().getPlayerManager().getPlayerList().forEach(p -> 
            p.networkHandler.sendPacket(packet));
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Инициализация мода Fire in the Hole!");

        // Регистрация команд для тестирования
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("firestreak")
                .requires(source -> source.hasPermissionLevel(2)) // Требуется уровень оператора
                .then(literal("settime")
                    .then(argument("minutes", integer(1))
                        .executes(context -> {
                            int minutes = context.getArgument("minutes", Integer.class);
                            SESSION_DURATION = minutes * 60 * 1000;
                            context.getSource().sendMessage(Text.literal("§b❄ Время сессии установлено на " + minutes + " минут").formatted(Formatting.AQUA));
                            return 1;
                        })))
                .then(literal("setactivity")
                    .then(argument("1 or 0", integer(0, 1))
                        .executes(context -> {
                            if (context.getSource().isExecutedByPlayer()) {
                                ServerPlayerEntity player = context.getSource().getPlayer();
                                PlayerFireData data = playerData.get(player.getUuid());
                                if (data != null) {
                                    int activity = context.getArgument("1 or 0", Integer.class);
                                    long currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000);
                                    
                                    if (activity == 1) {
                                        // Устанавливаем активность на сегодня
                                        data.setLastStreakDate(currentDay);
                                        data.setStreak(data.getStreak() + 1);
                                        context.getSource().sendMessage(Text.literal("§b❄ Статус активности установлен на сегодня! Огонечек: " + data.getStreak()));
                                    } else {
                                        // Сбрасываем активность
                                        if (data.getLastStreakDate() == currentDay) {
                                            data.setStreak(Math.max(0, data.getStreak() - 1));
                                        }
                                        data.setLastStreakDate(currentDay - 1);
                                        context.getSource().sendMessage(Text.literal("§b❄ Статус активности сброшен! Огонечек: " + data.getStreak()));
                                    }
                                    
                                    // Обновляем таб
                                    updatePlayerListForAll(player);
                                    saveData();
                                }
                            }
                            return 1;
                        })))
                .then(literal("skipday")
                    .executes(context -> {
                        // Получаем текущий день и добавляем 1
                        long currentTime = System.currentTimeMillis();
                        long nextDay = (currentTime / (24 * 60 * 60 * 1000) + 1) * (24 * 60 * 60 * 1000);
                        
                        // Для всех игроков на сервере
                        for (ServerPlayerEntity player : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                            PlayerFireData data = playerData.get(player.getUuid());
                            if (data != null) {
                                // Если у игрока не выполнено сегодняшнее задание, сбрасываем стрик
                                if (data.getLastStreakDate() < currentTime / (24 * 60 * 60 * 1000)) {
                                    data.setStreak(0);
                                    player.sendMessage(Text.literal("§b❄ День пропущен! Ваш огонечек сброшен!"));
                                } else {
                                    player.sendMessage(Text.literal("§b❄ День пропущен! Ваш огонечек сохранен."));
                                }
                                // Сбрасываем текущую сессию
                                data.setSessionStart(0);
                                // Обновляем таб после изменения дня
                                updatePlayerListForAll(player);
                            }
                        }
                        
                        context.getSource().sendMessage(Text.literal("§b❄ День успешно пропущен для всего сервера!"));
                        saveData();
                        return 1;
                    }))
                .then(literal("status")
                    .executes(context -> {
                        if (context.getSource().isExecutedByPlayer()) {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            PlayerFireData data = playerData.get(player.getUuid());
                            if (data != null) {
                                long currentTime = System.currentTimeMillis();
                                long currentDay = currentTime / (24 * 60 * 60 * 1000);
                                boolean isActive = data.getLastStreakDate() == currentDay;
                                context.getSource().sendMessage(Text.literal("")
                                    .append(Text.literal("§b❄ Статус Огонечка §b❄").formatted(Formatting.AQUA))
                                    .append(Text.literal("\nТекущий: ").formatted(Formatting.YELLOW))
                                    .append(Text.literal(String.valueOf(data.getStreak())).formatted(isActive ? Formatting.GREEN : Formatting.GRAY))
                                    .append(Text.literal("\nРекорд: ").formatted(Formatting.YELLOW))
                                    .append(Text.literal(String.valueOf(data.getMaxStreak())).formatted(Formatting.LIGHT_PURPLE))
                                    .append(Text.literal("\nСтатус сегодня: ").formatted(Formatting.YELLOW))
                                    .append(Text.literal(isActive ? "Выполнено ✓" : "Не выполнено ✗").formatted(isActive ? Formatting.GREEN : Formatting.RED))
                                );
                                if (data.hasActiveSession()) {
                                    long timeLeft = (data.getSessionStart() + SESSION_DURATION - currentTime) / 1000;
                                    if (timeLeft > 0) {
                                        context.getSource().sendMessage(Text.literal("Осталось: " + (timeLeft / 60) + "м " + (timeLeft % 60) + "с")
                                            .formatted(Formatting.AQUA));
                                    }
                                }
                            }
                        }
                        return 1;
                    }))
                .then(literal("reset")
                    .executes(context -> {
                        if (context.getSource().isExecutedByPlayer()) {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            PlayerFireData data = playerData.get(player.getUuid());
                            if (data != null) {
                                data.setStreak(0);
                                data.setLastStreakDate(0);
                                data.setSessionStart(0);
                                context.getSource().sendMessage(Text.literal("§b❄ Огонёчек сброшен!"));
                                saveData();
                            }
                        }
                        return 1;
                    }))
                .then(literal("set")
                    .then(argument("streak", integer(0))
                        .executes(context -> {
                            if (context.getSource().isExecutedByPlayer()) {
                                ServerPlayerEntity player = context.getSource().getPlayer();
                                PlayerFireData data = playerData.get(player.getUuid());
                                if (data != null) {
                                    int streak = context.getArgument("streak", Integer.class);
                                    data.setStreak(streak);
                                    context.getSource().sendMessage(Text.literal("§b❄ Огонечек установлен на " + streak));
                                    // Обновляем таб после установки стрика
                                    updatePlayerListForAll(player);
                                    saveData();
                                }
                            }
                            return 1;
                        }))));
        });

        // Регистрация плейсхолдеров для Styled Chat и Tab List
        Placeholders.register(new net.minecraft.util.Identifier(MOD_ID, "streak"),
            (PlaceholderContext ctx, String arg) -> {
                if (ctx.player() != null) {
                    PlayerFireData data = playerData.get(ctx.player().getUuid());
                    if (data != null) {
                        long currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000);
                        boolean isActive = data.getLastStreakDate() == currentDay;
                        String streakText = "🔥" + data.getStreak();
                        return PlaceholderResult.value(Text.literal(streakText)
                            .formatted(isActive ? Formatting.GOLD : Formatting.GRAY));
                    }
                }
                return PlaceholderResult.value(Text.literal(""));
            });

        // Регистрация плейсхолдера для максимального стрика
        Placeholders.register(new net.minecraft.util.Identifier(MOD_ID, "max_streak"),
            (PlaceholderContext ctx, String arg) -> {
                if (ctx.player() != null) {
                    PlayerFireData data = playerData.get(ctx.player().getUuid());
                    if (data != null) {
                        String maxStreakText = "🏆" + data.getMaxStreak();
                        return PlaceholderResult.value(Text.literal(maxStreakText)
                            .formatted(Formatting.LIGHT_PURPLE));
                    }
                }
                return PlaceholderResult.value(Text.literal(""));
            });

        // Загрузка данных при старте сервера
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                Path configDir = server.getRunDirectory().toPath().resolve("config").resolve(MOD_ID);
                Files.createDirectories(configDir);
                saveFile = configDir.resolve("player_data.json").toFile();
                loadData();
            } catch (IOException e) {
                LOGGER.error("Ошибка при создании файла сохранения", e);
            }
        });

        // Сохранение данных при выключении сервера
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveData());

        // Обработка входа игрока
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            UUID playerUuid = player.getUuid();
            
            PlayerFireData data = playerData.computeIfAbsent(playerUuid, k -> new PlayerFireData());
            long currentTime = System.currentTimeMillis();
            long currentDay = currentTime / (24 * 60 * 60 * 1000);

            // Проверяем, был ли стрик получен вчера
            if (data.getLastStreakDate() > 0 && currentDay - data.getLastStreakDate() > 1) {
                // Пропущен день - сброс стрика
                data.setStreak(0);
                player.sendMessage(Text.literal("§cВы пропустили день! Ваш огонечек сброшен!"));
            }

            // Проверяем, не выполнено ли уже сегодняшнее задание
            boolean isCompletedToday = data.getLastStreakDate() == currentDay;

            if (!isCompletedToday) {
                if (!data.hasActiveSession()) {
                    // Начинаем новую сессию только если сегодня ещё не выполнено задание
                    data.setSessionStart(currentTime);
                    player.sendMessage(Text.literal("§6Для поддержки огонечка оставайся на сервере в течении 15 минут"));
                    player.sendMessage(Text.literal("§7Текущий огонечек: " + data.getStreak() + " дней"));
                    player.sendMessage(Text.literal("§7Рекорд: " + data.getMaxStreak() + " дней"));
                } else {
                    // Сессия уже идет
                    long timeLeft = (data.getSessionStart() + SESSION_DURATION - currentTime) / 1000;
                    if (timeLeft > 0) {
                        player.sendMessage(Text.literal("§aПродолжайте играть еще " + (timeLeft / 60) + " минут " + (timeLeft % 60) + " секунд!"));
                    }
                }
            }
        });

        // Проверка времени каждый тик
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long currentTime = System.currentTimeMillis();
            long currentDay = currentTime / (24 * 60 * 60 * 1000);
            
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID playerUuid = player.getUuid();
                PlayerFireData data = playerData.get(playerUuid);
                
                if (data != null && data.hasActiveSession()) {
                    long sessionTime = currentTime - data.getSessionStart();
                    
                    // Проверка завершения сессии
                    if (sessionTime >= SESSION_DURATION) {
                        // Проверяем, не получал ли игрок уже стрик сегодня
                        if (data.getLastStreakDate() < currentDay) {
                            data.setStreak(data.getStreak() + 1);
                            data.setLastStreakDate(currentDay);
                            player.sendMessage(Text.literal("§a§lПоздравляем! Ваш огонечек увеличен до " + data.getStreak() + " дней!"));
                            // Обновляем таб после изменения стрика
                            updatePlayerListForAll(player);
                            saveData();
                        }
                        data.setSessionStart(0);
                    }
                }
            }
        });
    }

    private void loadData() {
        if (saveFile.exists()) {
            try (Reader reader = new FileReader(saveFile)) {
                Map<String, PlayerFireData> loadedData = GSON.fromJson(reader, 
                    new com.google.gson.reflect.TypeToken<Map<String, PlayerFireData>>(){}.getType());
                loadedData.forEach((uuid, data) -> playerData.put(UUID.fromString(uuid), data));
                LOGGER.info("Данные игроков успешно загружены");
            } catch (IOException e) {
                LOGGER.error("Ошибка при загрузке данных", e);
            }
        }
    }

    private void saveData() {
        try (Writer writer = new FileWriter(saveFile)) {
            Map<String, PlayerFireData> saveData = new HashMap<>();
            playerData.forEach((uuid, data) -> saveData.put(uuid.toString(), data));
            GSON.toJson(saveData, writer);
            LOGGER.info("Данные игроков успешно сохранены");
        } catch (IOException e) {
            LOGGER.error("Ошибка при сохранении данных", e);
        }
    }
} 