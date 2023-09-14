package com.notion.telegrambot.service;

import com.notion.telegrambot.config.BotConfig;
import com.notion.telegrambot.model.Notification;
import com.notion.telegrambot.model.NotificationRepository;
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

import static com.notion.telegrambot.service.tools.ConvertTool.*;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {
    BotConfig config;
    UserRepository userRepository;
    NotificationRepository notificationRepository;

    Map<Long, Thread> threads = new HashMap<>();

    Map<Long, Session> sessions = new HashMap<>() {
        private static final int SESSION_TIME_MINUTES = 10;

        @Override
        public Session put(Long key, Session value) {
            Calendar session_time = new GregorianCalendar();
            session_time.add(Calendar.SECOND, -SESSION_TIME_MINUTES);
            for (Entry<Long, Session> entry : this.entrySet()) {
                Calendar entry_calendar = new GregorianCalendar();
                entry_calendar.setTimeInMillis(entry.getValue().getCreated());
                if (session_time.after(entry_calendar)) {
                    sessions.remove(entry.getKey());
                }
            }
            return super.put(key, value);
        }
    };

    @Autowired
    public TelegramBot(BotConfig config, UserRepository userRepository, NotificationRepository notificationRepository) {
        super(config.getToken());
        this.config = config;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        // menu
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/state", "notifications, interval and activity"));
        listOfCommands.add(new BotCommand("/run", "run notifications"));
        listOfCommands.add(new BotCommand("/stop", "stop notifications"));
        listOfCommands.add(new BotCommand("/add", "add new notification"));
        listOfCommands.add(new BotCommand("/delete", "delete notification"));
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
                    if (threads.containsKey(chatId)) sendMessage(chatId, "Notifications are active");
                    else sendMessage(chatId, "Notifications are disabled");
                    int interval = convertToMinutes(user.getInterval());
                    if (interval != 1) sendMessage(chatId, "Your current interval is " + interval + " minutes");
                    else sendMessage(chatId, "Your current interval is " + interval + " minute");
                    if (user.getNotifications().isEmpty()) sendMessage(chatId, "You don't have any notifications");
                    else show(user);
                    sessions.remove(chatId);
                }
                case "/start" -> {
                    startCommand(update.getMessage(), user);
                    sessions.remove(chatId);
                }
                case "/run" -> {
                    if (user.getNotifications().isEmpty()) {
                        sendMessage(chatId, "You don't have any notifications");
                    } else {
                        if (!threads.containsKey(chatId)) {
                            run(user);
                            sendMessage(chatId, "Notifications are running");
                            userRepository.save(user);
                        } else sendMessage(chatId, "Notifications are already running");
                    }
                    sessions.remove(chatId);
                }
                case "/stop" -> {
                    if (threads.containsKey(chatId)) {
                        stop(chatId);
                        sendMessage(chatId, "Notifications are stopped");
                        userRepository.save(user);
                    } else {
                        sendMessage(chatId, "Notifications have already been stopped");
                    }
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
                    if (user.getNotifications().isEmpty()) sendMessage(chatId, "You don't have any notifications");
                    else {
                        show(user);
                        sendMessage(chatId, "Enter number of one you want to delete, please");
                        sessions.put(chatId, new Session("delete"));
                    }
                }
                default -> {
                    if (sessions.containsKey(chatId)) handleSession(user, messageText);
                    else sendMessage(chatId, "Sorry, but I don't understand you");
                }
            }
        }
    }

    public void handleSession(User user, String messageText) {
        long chatId = user.getChatId();
        String session = sessions.get(chatId).getState();

        switch (session) {
            case "set" -> {
                try {
                    int interval = Integer.parseInt(messageText);
                    user.setInterval(convertFromMinutes(interval));
                    if (threads.containsKey(chatId)) {
                        stop(chatId);
                        run(user);
                    }

                    userRepository.save(user);
                    sessions.remove(chatId);
                    if (interval != 1) sendMessage(chatId, "Your interval was changed to " + interval + " minutes");
                    else sendMessage(chatId, "Your interval was changed to " + interval + " minute");
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Enter valid number, please");
                }
            }
            case "add" -> {
                Notification notification = new Notification(messageText);
                user.addNotification(notification);
                sessions.remove(chatId);
                notificationRepository.save(notification);
                userRepository.save(user);
                sendMessage(chatId, "You successfully added new notification");
            }
            case "delete" -> {
                int index = Integer.parseInt(messageText) - 1;
                List<Notification> notifications = user.getNotifications();
                try {
                    if (index < notifications.size() && index >= 0) {
                        Notification notification = notifications.remove(index);
                        user.setNotifications(notifications);
                        userRepository.save(user);
                        notificationRepository.deleteById(notification.getId());
                        sessions.remove(chatId);
                        sendMessage(chatId, "You successfully deleted the notification");
                    } else sendMessage(chatId, "Enter valid number, please");
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Enter valid number, please");
                }
            }
        }

    }

    public void startCommand(Message message, User user) {
        if (!userRepository.existsById(message.getChatId())) {
            user.setChatId(message.getChatId());
            user.setInterval(DEFAULT_INTERVAL); // 45 minutes by default
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
        List<String> notions = user.getNotifications().stream().map(Notification::getDescription).toList();
        Thread thread = start(user.getChatId(), user.getInterval(), notions.toArray(new String[0]));
        threads.put(user.getChatId(), thread);
    }

    @SuppressWarnings("BusyWait")
    private Thread start(long chatId, long interval, String[] notions) {
        Runnable task = () -> {
            while (true) {
                for (String notion : notions) {
                    sendMessage(chatId, notion);
                }
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
            }
        };
        Thread thread = new Thread(task);
        thread.start();
        return thread;
    }

    public void show(User user) {
        StringJoiner stringJoiner = new StringJoiner("\n");
        stringJoiner.add("Your notifications:");
        List<Notification> notifications = user.getNotifications();
        for (int i = 0; i < notifications.size(); i++) {
            stringJoiner.add(String.format("%d. %s", i + 1, notifications.get(i).getDescription()));
        }
        sendMessage(user.getChatId(), stringJoiner.toString());
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
