package com.notion.telegrambot.service.tools;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SendMessageTools {
    /**
     * Send text message
     */
    public static void sendMessage(TelegramLongPollingBot bot, long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            bot.execute(message);
        } catch (TelegramApiException exception) {
            log.error("Unable to send message");
        }
    }


    /**
     * Send text message with InlineKeyboardMarkup
     */
    public static void sendMessage(TelegramLongPollingBot bot, long chatId, String text, InlineKeyboardMarkup inlineKeyboardMarkup) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(inlineKeyboardMarkup);
        try {
            bot.execute(message);
        } catch (TelegramApiException exception) {
            log.error("Unable to send inline-keyboard message");
        }
    }


    /**
     * Send text message with File
     */
    public static void sendMessage(TelegramLongPollingBot bot, long chatId, File file) {
        SendDocument message = new SendDocument();
        message.setChatId(String.valueOf(chatId));
        message.setDocument(new InputFile(file));
        try {
            bot.execute(message);
        } catch (TelegramApiException exception) {
            log.error("Unable to send file message");
        }
    }


    /**
     * Send EditTextMessage
     */
    public static void sendMessage(TelegramLongPollingBot bot, EditMessageText editMessageText, Message message) {
        int messageId = message.getMessageId();
        long chatId = message.getChatId();
        editMessageText.setChatId(String.valueOf(chatId));
        editMessageText.setMessageId(messageId);
        try {
            bot.execute(editMessageText);
        } catch (TelegramApiException exception) {
            log.error("Unable to send edit message");
        }
    }


    /**
     * Send text message with MediaGroup
     */
    public static void sendMessage(TelegramLongPollingBot bot, long chatId, List<File> files, String caption) {
        SendMediaGroup mediaGroup = new SendMediaGroup();
        mediaGroup.setChatId(String.valueOf(chatId));
        List<InputMedia> inputMediaList = new ArrayList<>();
        for (File file : files) {
            InputMedia inputMedia = new InputMediaPhoto();
            inputMedia.setMedia(file, file.getName());
            if (inputMediaList.isEmpty()) inputMedia.setCaption(caption);
            inputMediaList.add(inputMedia);
        }
        mediaGroup.setMedias(inputMediaList);
        try {
            bot.execute(mediaGroup);
        } catch (TelegramApiException exception) {
            log.error("Unable to send media group message");
        }
    }
}

