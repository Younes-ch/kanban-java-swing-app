import java.io.Serializable;
import java.time.LocalDateTime;

public class ChatMessage implements Serializable {
    private int messageId;
    private int userId;
    private String username;
    private String content;
    private LocalDateTime createdAt;

    // Constructor used by server when retrieving from DB
    public ChatMessage(int messageId, int userId, String username, String content, LocalDateTime createdAt) {
        this.messageId = messageId;
        this.userId = userId;
        this.username = username;
        this.content = content;
        this.createdAt = createdAt;
    }

    // Getters
    public int getMessageId() { return messageId; }
    public int getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // toString (optional, for debugging)
    @Override
    public String toString() {
        return "ChatMessage{" +
                "messageId=" + messageId +
                ", userId=" + userId +
                ", username='" + username + '\'' +
                ", content='" + content + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}