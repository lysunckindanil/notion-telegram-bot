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
    private boolean active;
    private long interval;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "user")
    public List<Notification> notifications;

    public void addNotification(Notification notification) {
        notification.setUser(this);
        notifications.add(notification);
    }

    public boolean deleteNotificationByIndex(int index, NotificationRepository notificationRepository) {
        if (index < notifications.size() && index >= 0) {
            long id = notifications.get(index).getId();
            notifications.remove(index);
            notificationRepository.deleteById(id);
            return true;
        }
        return false;
    }


    @PrePersist
    public void init() {
        active = false;
    }

}
