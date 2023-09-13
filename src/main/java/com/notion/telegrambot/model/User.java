package com.notion.telegrambot.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    private Long chatId;
    private long interval;
    @OneToMany(fetch = FetchType.EAGER, mappedBy = "user")
    private List<Notification> notifications;

    public void addNotification(Notification notification) {
        notification.setUser(this);
        notifications.add(notification);
    }

}
