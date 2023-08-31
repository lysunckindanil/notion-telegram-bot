package com.notion.telegrambot.service;

import com.notion.telegrambot.config.BotConfig;
import com.notion.telegrambot.model.User;
import com.notion.telegrambot.model.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.*;

import static com.notion.telegrambot.service.tools.SendMessageTools.sendMessage;

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
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/run", "run all notifications"));
        listOfCommands.add(new BotCommand("/stop", "stop all notifications"));
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
            switch (messageText) {
                case "/start" -> {
                    startCommand(update.getMessage());
                    sessions.remove(chatId);
                }
                case "/run" -> {
                    if (userRepository.findById(chatId).get().getNotions().isBlank())
                        sendMessage(this, chatId, "You don't have any notifications");
                    else run(chatId);
                    sessions.remove(chatId);
                }
                case "/stop" -> {
                    stop(chatId);
                    sessions.remove(chatId);
                }
                case "/interval" -> {
                    int interval = (int) (userRepository.findById(chatId).get().getInterval() / 1000 / 60);
                    if (interval != 1) sendMessage(this, chatId, "Your current interval is " + interval + " minutes");
                    else sendMessage(this, chatId, "Your current interval is " + interval + " minute");
                    sessions.remove(chatId);
                }
                case "/show" -> {
                    if (userRepository.findById(chatId).get().getNotions().isBlank())
                        sendMessage(this, chatId, "You don't have any notifications");
                    else show(chatId);
                    sessions.remove(chatId);
                }
                // session required
                case "/set" -> {
                    sendMessage(this, chatId, "Enter the interval time in minutes, please");
                    sessions.put(chatId, new Session("set"));
                }
                case "/add" -> {
                    sendMessage(this, chatId, "Enter new notification, please");
                    sessions.put(chatId, new Session("add"));
                }
                case "/delete" -> {
                    if (userRepository.findById(chatId).get().getNotions().isBlank())
                        sendMessage(this, chatId, "You don't have any notifications");
                    else {
                        show(chatId);
                        sendMessage(this, chatId, "Enter number of one you want to delete, please");
                        sessions.put(chatId, new Session("delete"));
                    }
                }
                default -> {
                    if (sessions.containsKey(chatId)) handleSession(chatId, messageText);
                    else sendMessage(this, chatId, "Sorry, but I don't understand you");
                }
            }
        }
    }

    public void handleSession(long chatId, String messageText) {
        String session = sessions.get(chatId).getState();
        User user = userRepository.findById(chatId).get();
        switch (session) {
            case "set" -> {
                try {
                    long interval = Long.parseLong(messageText);
                    user.setInterval(interval * 60 * 1000);
                    if (interval != 1)
                        sendMessage(this, chatId, "Your interval was changed to " + interval + " minutes");
                    else sendMessage(this, chatId, "Your interval was changed to " + interval + " minute");
                    sessions.remove(chatId);
                    userRepository.save(user);
                    if (user.isActive()) {
                        stop(chatId);
                        run(chatId);
                    }
                } catch (NumberFormatException e) {
                    sendMessage(this, chatId, "Enter valid number, please");
                }
            }
            case "add" -> {
                String notions = user.getNotions();
                if (notions.isEmpty()) user.setNotions(messageText);
                else user.setNotions(notions + "%%" + messageText);
                sendMessage(this, chatId, "You successfully added new notification");
                sessions.remove(chatId);
                userRepository.save(user);
            }
            case "delete" -> {
                try {
                    List<String> notions = new ArrayList<>(Arrays.stream(user.getNotions().split("%%")).toList());
                    int i = Integer.parseInt(messageText);
                    if (i - 1 < notions.size() && i > 0) {
                        notions.remove(i - 1);
                        sendMessage(this, chatId, "You successfully deleted the notification");
                        user.setNotions(String.join("%%", notions));
                        sessions.remove(chatId);
                        userRepository.save(user);
                    } else sendMessage(this, chatId, "Enter valid number, please");
                } catch (NumberFormatException e) {
                    sendMessage(this, chatId, "Enter valid number, please");
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

    public void run(long chatId) {
        User user = userRepository.findById(chatId).get();
        String[] notions = user.getNotions().split("%%");
        if (!user.isActive()) {
            if (notions.length > 0) {
                user.setActive(true);
                threads.put(chatId, start(chatId, user.getInterval(), notions));
                userRepository.save(user);
                sendMessage(this, chatId, "Notifications are running");
            } else {
                sendMessage(this, chatId, "Please, add notifications firstly!");
            }
        } else {
            sendMessage(this, chatId, "Notifications are already running");
        }
    }

    public void stop(long chatId) {
        User user = userRepository.findById(chatId).get();
        if (user.isActive()) {
            user.setActive(false);
            Thread thread = threads.get(chatId);
            if (thread != null) {
                thread.interrupt();
                threads.remove(chatId);
            }
            userRepository.save(user);
            sendMessage(this, chatId, "Notifications are stopped");
        } else {
            sendMessage(this, chatId, "Notifications have already been stopped");
        }
    }

    @SuppressWarnings("BusyWait")
    public Thread start(long chatId, long interval, String[] notions) {
        Runnable task = () -> {
            while (true) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
                for (String notion : notions) {
                    sendMessage(this, chatId, notion);
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
        sendMessage(this, chatId, stringJoiner.toString());
    }

    @Override
    public String getBotUsername() {
        return config.getName();
    }
}
