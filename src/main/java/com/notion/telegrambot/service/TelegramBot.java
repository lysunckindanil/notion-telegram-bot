package com.notion.telegrambot.service;

import com.notion.telegrambot.config.BotConfig;
import com.notion.telegrambot.model.User;
import com.notion.telegrambot.model.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.*;

@SuppressWarnings("OptionalGetWithoutIsPresent")
@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {
    BotConfig config;
    UserRepository userRepository;

    Map<Long, Thread> threads = new HashMap<>();

    Map<Long, Session> sessions = new HashMap<>();

    @Autowired
    public TelegramBot(BotConfig config, UserRepository userRepository) {
        super(config.getToken());
        this.config = config;
        this.userRepository = userRepository;
        // menu
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/state", "check active or not"));
        listOfCommands.add(new BotCommand("/run", "run notifications"));
        listOfCommands.add(new BotCommand("/stop", "stop notifications"));
        listOfCommands.add(new BotCommand("/show", "show your notifications"));
        listOfCommands.add(new BotCommand("/add", "add new notification"));
        listOfCommands.add(new BotCommand("/delete", "delete notification"));
        listOfCommands.add(new BotCommand("/interval", "get your current interval"));
        listOfCommands.add(new BotCommand("/set", "set interval"));
        try {
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException exception) {
            log.error("Error setting bot command list " + exception.getMessage());
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            User user = userRepository.findById(chatId).orElse(new User());
            switch (messageText) {
                case "/state" -> {
                    if (user.isActive()) sendMessage(chatId, "Notifications are active");
                    else sendMessage(chatId, "Notifications are disabled");
                    sessions.remove(chatId);
                }
                case "/start" -> {
                    startCommand(update.getMessage());
                    sessions.remove(chatId);
                }
                case "/run" -> {
                    if (user.getNotions().isBlank()) {
                        sendMessage(chatId, "You don't have any notifications");
                    } else {
                        if (!user.isActive()) {
                            run(user);
                            sendMessage(chatId, "Notifications are running");
                            user.setActive(true);
                        } else sendMessage(chatId, "Notifications are already running");
                    }
                    sessions.remove(chatId);
                }
                case "/stop" -> {
                    if (user.isActive()) {
                        stop(chatId);
                        sendMessage(chatId, "Notifications are stopped");
                        user.setActive(false);
                    } else {
                        sendMessage(chatId, "Notifications have already been stopped");
                    }
                    sessions.remove(chatId);
                }
                case "/interval" -> {
                    int interval = (int) (user.getInterval() / 1000 / 60);
                    if (interval != 1) sendMessage(chatId, "Your current interval is " + interval + " minutes");
                    else sendMessage(chatId, "Your current interval is " + interval + " minute");
                    sessions.remove(chatId);
                }
                case "/show" -> {
                    if (user.getNotions().isBlank())
                        sendMessage(chatId, "You don't have any notifications");
                    else show(chatId);
                    sessions.remove(chatId);
                }
                // session required
                case "/set" -> {
                    sendMessage(chatId, "Enter the interval time in minutes, please");
                    sessions.put(chatId, new Session("set"));
                }
                case "/add" -> {
                    sendMessage(chatId, "Enter new notification, please");
                    sessions.put(chatId, new Session("add"));
                }
                case "/delete" -> {
                    if (user.getNotions().isBlank())
                        sendMessage(chatId, "You don't have any notifications");
                    else {
                        show(chatId);
                        sendMessage(chatId, "Enter number of one you want to delete, please");
                        sessions.put(chatId, new Session("delete"));
                    }
                }
                default -> {
                    if (sessions.containsKey(chatId)) handleSession(user, messageText);
                    else sendMessage(chatId, "Sorry, but I don't understand you");
                }
            }
            userRepository.save(user);
        }
    }

    public void handleSession(User user, String messageText) {
        long chatId = user.getChatId();
        String session = sessions.get(chatId).getState();

        switch (session) {
            case "set" -> {
                try {
                    long interval = Long.parseLong(messageText);
                    user.setInterval(interval * 60 * 1000);
                    if (interval != 1) sendMessage(chatId, "Your interval was changed to " + interval + " minutes");
                    else sendMessage(chatId, "Your interval was changed to " + interval + " minute");
                    if (user.isActive()) {
                        stop(chatId);
                        run(user);
                    }
                    sessions.remove(chatId);

                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Enter valid number, please");
                }
            }
            case "add" -> {
                String notions = user.getNotions();
                if (notions.isEmpty()) user.setNotions(messageText);
                else user.setNotions(notions + "%%" + messageText);
                sendMessage(chatId, "You successfully added new notification");
                sessions.remove(chatId);
            }
            case "delete" -> {
                try {
                    List<String> notions = new ArrayList<>(Arrays.stream(user.getNotions().split("%%")).toList());
                    int i = Integer.parseInt(messageText);
                    if (i - 1 < notions.size() && i > 0) {
                        notions.remove(i - 1);
                        sendMessage(chatId, "You successfully deleted the notification");
                        user.setNotions(String.join("%%", notions));
                        sessions.remove(chatId);
                    } else sendMessage(chatId, "Enter valid number, please");
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Enter valid number, please");
                }
            }
        }
    }

    public void startCommand(Message message) {
        if (!userRepository.existsById(message.getChatId())) {
            User user = new User();
            user.setChatId(message.getChatId());
            user.setInterval(45 * 60 * 1000); // 45 minutes by default
            userRepository.save(user);
        }
    }

    public void stop(long chatId) {
        Thread thread = threads.get(chatId);
        if (thread != null) {
            thread.interrupt();
            threads.remove(chatId);
        }
    }

    public void run(User user) {
        String[] notions = user.getNotions().split("%%");
        Thread thread = start(user.getChatId(), user.getInterval(), notions);
        threads.put(user.getChatId(), thread);
    }

    @SuppressWarnings("BusyWait")
    private Thread start(long chatId, long interval, String[] notions) {
        Runnable task = () -> {
            while (true) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
                for (String notion : notions) {
                    sendMessage(chatId, notion);
                }
            }
        };
        Thread thread = new Thread(task);
        thread.start();
        return thread;
    }

    public void show(long chatId) {
        StringJoiner stringJoiner = new StringJoiner("\n");
        stringJoiner.add("Your notifications:");
        var notions = userRepository.findById(chatId).get().getNotions().split("%%");
        for (int i = 0; i < notions.length; i++) {
            stringJoiner.add(String.format("%d. %s", i + 1, notions[i]));
        }
        sendMessage(chatId, stringJoiner.toString());
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException exception) {
            log.error("Unable to send message");
        }
    }

    @Override
    public String getBotUsername() {
        return config.getName();
    }
}
