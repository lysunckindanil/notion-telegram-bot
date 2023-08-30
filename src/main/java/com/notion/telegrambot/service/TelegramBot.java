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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.notion.telegrambot.service.tools.SendMessageTools.sendMessage;

@SuppressWarnings("OptionalGetWithoutIsPresent")
@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {
    BotConfig config;
    UserRepository userRepository;

    Map<Long, Thread> threads = new HashMap<>();

    @Autowired
    public TelegramBot(BotConfig config, UserRepository userRepository) {
        super(config.getToken());
        this.config = config;
        this.userRepository = userRepository;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/run", "add all notifications"));
        listOfCommands.add(new BotCommand("/stop", "stop all notifications"));
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
            log.info(messageText);
            switch (messageText) {
                case "/start" -> startCommand(update.getMessage());
                case "/run" -> run(chatId);
                case "/stop" -> stop(chatId);
            }
        }
    }

    public void startCommand(Message message) {
        if (!userRepository.existsById(message.getChatId())) {
            User user = new User();
            user.setChatId(message.getChatId());
            user.setInterval(2 * 1000); // 60 minutes by default
            user.setNotions("Выпрями спину!%%Говори четче!");
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
                log.info(threads.toString());
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
                log.info(threads.toString());
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

    @Override
    public String getBotUsername() {
        return config.getName();
    }
}
