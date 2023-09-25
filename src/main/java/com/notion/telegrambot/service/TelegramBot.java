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

    // threads with notifications running at the moment
    Map<Long, Thread> threads = new HashMap<>();

    // user sessions that are cleared every 10 minutes
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

                // get current information about your notifications and interval
                case "/state" -> {
                    if (threads.containsKey(chatId)) sendMessage(chatId, "Notifications are active");
                    else sendMessage(chatId, "Notifications are disabled");
                    int interval = convertToMinutes(user.getInterval());
                    if (interval != 1) sendMessage(chatId, "Your current interval is " + interval + " minutes");
                    else sendMessage(chatId, "Your current interval is " + interval + " minute");
                    show(user);
                    sessions.remove(chatId);
                }

                // start command (add to database and help info)
                case "/start" -> {
                    startCommand(update.getMessage(), user);
                    sessions.remove(chatId);
                }

                // run notifications
                case "/run" -> {
                    if (user.getNotifications().isEmpty()) {
                        sendMessage(chatId, "You don't have any notifications");
                    } else {
                        if (!threads.containsKey(chatId)) {
                            run(user);
                            sendMessage(chatId, "Notifications are running");
                        } else sendMessage(chatId, "Notifications are already running");
                    }
                    sessions.remove(chatId);
                }

                // stop notifications
                case "/stop" -> {
                    if (threads.containsKey(chatId)) {
                        stop(chatId);
                        sendMessage(chatId, "Notifications are stopped");
                    } else {
                        sendMessage(chatId, "Notifications have already been stopped");
                    }
                    sessions.remove(chatId);
                }

                /* session required */
                // to set interval
                case "/set" -> {
                    sendMessage(chatId, "Enter the interval time in minutes, please");
                    sessions.put(chatId, new Session("set"));
                }

                // to add notification
                case "/add" -> {
                    sendMessage(chatId, "Enter new notification, please");
                    sessions.put(chatId, new Session("add"));
                }

                // to delete notification
                case "/delete" -> {
                    if (user.getNotifications().isEmpty()) sendMessage(chatId, "You don't have any notifications");
                    else {
                        show(user);
                        sendMessage(chatId, "Enter number of one you want to delete, please");
                        sessions.put(chatId, new Session("delete"));
                    }
                }

                // if no one's match checks whether it's session message or not otherwise default message
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
                // gets integer if it's not appropriate format then repeats query
                try {
                    int interval = Integer.parseInt(messageText);
                    user.setInterval(convertFromMinutes(interval));
                    if (threads.containsKey(chatId)) {
                        stop(chatId);
                        run(user);
                    }
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
                sendMessage(chatId, "You successfully added new notification");
            }

            case "delete" -> {
                // gets integer in the notifications list range if it's not appropriate format then repeats query
                int index = Integer.parseInt(messageText) - 1;
                List<Notification> notifications = user.getNotifications();
                try {
                    Notification notification = notifications.remove(index);
                    user.setNotifications(notifications);
                    notificationRepository.deleteById(notification.getId());
                    sessions.remove(chatId);
                    sendMessage(chatId, "You successfully deleted the notification");
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    sendMessage(chatId, "Enter valid number, please");
                }
            }
        }
        userRepository.save(user);

    }

    public void startCommand(Message message, User user) {
        if (!userRepository.existsById(message.getChatId())) {
            // if user doesn't exist adds him to database with default interval
            user.setChatId(message.getChatId());
            user.setInterval(DEFAULT_INTERVAL); // 45 minutes by default
            userRepository.save(user);
        }
        String text = """
                Hello! I can send messages with certain interval. You can control me by sending these commands:
                /state - show notifications, whether they are running, and the interval.
                /run - start sending notifications.
                /stop - stop sending notifications.
                /add - add a notification (after the call, the bot asks for the notification text).
                /delete - delete the notification (after the call, the bot sends a list of notifications and asks you to specify its number).""";
        sendMessage(user.getChatId(), text);
    }

    public void stop(long chatId) {
        threads.remove(chatId);
    }

    @SuppressWarnings("BusyWait")
    private void run(User user) {
        // runs thread and adds to threads map
        // the thread interrupts itself if it's no more in the threads map
        List<String> notions = user.getNotifications().stream().map(Notification::getDescription).toList();
        long chatId = user.getChatId();
        Runnable task = () -> {
            while (threads.containsKey(chatId)) {
                for (String notion : notions) {
                    sendMessage(chatId, notion);
                }
                try {
                    Thread.sleep(user.getInterval());
                } catch (InterruptedException e) {
                    break;
                }
            }
        };
        Thread thread = new Thread(task);
        threads.put(chatId, thread);
        thread.start();
    }

    public void show(User user) {
        // shows user notifications as a list
        if (user.getNotifications().isEmpty()) sendMessage(user.getChatId(), "You don't have any notifications");
        else {
            StringJoiner stringJoiner = new StringJoiner("\n");
            stringJoiner.add("Your notifications:");
            List<Notification> notifications = user.getNotifications();
            for (int i = 0; i < notifications.size(); i++) {
                stringJoiner.add(String.format("%d. %s", i + 1, notifications.get(i).getDescription()));
            }
            sendMessage(user.getChatId(), stringJoiner.toString());
        }
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
