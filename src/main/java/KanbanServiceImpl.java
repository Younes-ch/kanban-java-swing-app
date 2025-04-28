import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class KanbanServiceImpl extends UnicastRemoteObject implements KanbanService {
    private List<ClientListener> listeners = new CopyOnWriteArrayList<>();

    protected KanbanServiceImpl() throws RemoteException {
        super();
    }
    @Override
    public User authenticateUser(String username, String password) throws RemoteException {
        String sql = "SELECT id, username, created_at, updated_at FROM users WHERE username = ? AND password = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, password);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new User(
                        rs.getInt("id"),
                        rs.getString("username"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                );
            } else {
                return null;
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error during authentication", e);
        }
    }

    @Override
    public List<User> getUsers() throws RemoteException {
        List<User> users = new ArrayList<>();
        String sql = "SELECT id, username, created_at, updated_at FROM users ORDER BY username";
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                users.add(new User(
                        rs.getInt("id"),
                        rs.getString("username"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error fetching users", e);
        }
        return users;
    }


    @Override
    public synchronized void registerListener(ClientListener listener) throws RemoteException {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            System.out.println("Listener registered: " + listener);
        }
    }

    @Override
    public synchronized void unregisterListener(ClientListener listener) throws RemoteException {
        boolean removed = listeners.remove(listener);
        if (removed) {
            System.out.println("Listener unregistered: " + listener);
        }
    }


    @Override
    public List<Board> getBoards() throws RemoteException {
        List<Board> boards = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM boards ORDER BY id")) {

            while (rs.next()) {
                boards.add(new Board(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                ));
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error fetching boards", e);
        }
        return boards;
    }

    @Override
    public Board createBoard(String name) throws RemoteException {
        String sql = "INSERT INTO boards (name) VALUES (?) RETURNING id, created_at, updated_at";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt("id");
                LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                LocalDateTime updatedAt = rs.getTimestamp("updated_at").toLocalDateTime();
                notifyBoardListChanged();
                return new Board(id, name, createdAt, updatedAt);
            } else {
                throw new RemoteException("Failed to create board");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error creating board", e);
        }
    }

    @Override
    public List<Task> getTasks(int boardId) throws RemoteException {
        List<Task> tasks = new ArrayList<>();
        String sql = "SELECT * FROM tasks WHERE board_id = ? ORDER BY id";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, boardId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                tasks.add(new Task(
                        rs.getInt("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        TaskStatus.valueOf(rs.getString("status")),
                        rs.getInt("board_id"),
                        rs.getInt("user_id"),
                        rs.getInt("assignee_id"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                ));
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error fetching tasks", e);
        }
        return tasks;
    }

    @Override
    public List<Task> getTasksByBoard(int boardId) throws RemoteException {
        return getTasks(boardId);
    }

    @Override
    public void createTask(int boardId, int user_id, int assignee_id, String title, String description, TaskStatus status) throws RemoteException {
        String sql = """
                INSERT INTO tasks (board_id, user_id, assignee_id, title, description, status)
                VALUES (?, ?, ?, ?, ?, ?::task_status)
                RETURNING id, created_at, updated_at
            """;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, boardId);
            stmt.setInt(2, user_id);
            stmt.setInt(3, assignee_id);
            stmt.setString(4, title);
            stmt.setString(5, description);
            stmt.setString(6, status.name());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                notifyTaskUpdate(boardId);
            } else {
                throw new RemoteException("Failed to create task");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            if (e.getSQLState().startsWith("23")) { // Foreign key or other constraint violation
                throw new RemoteException("Error creating task: Invalid user or board reference.", e);
            }
            throw new RemoteException("Error creating task", e);
        }
    }

    @Override
    public void moveTask(int taskId, TaskStatus newStatus) throws RemoteException {
        String sql = "UPDATE tasks SET status = ?::task_status, updated_at = CURRENT_TIMESTAMP WHERE id = ? RETURNING board_id";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newStatus.name());
            stmt.setInt(2, taskId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int boardId = rs.getInt("board_id");
                notifyTaskUpdate(boardId);
            } else {
                throw new RemoteException("Failed to move task");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error moving task", e);
        }
    }

    @Override
    public void deleteTask(int taskId) throws RemoteException {
        String sql = "DELETE FROM tasks WHERE id = ? RETURNING board_id";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, taskId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int boardId = rs.getInt("board_id");
                notifyTaskUpdate(boardId);
            } else {
                throw new RemoteException("Failed to delete task");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error deleting task", e);
        }
    }

    @Override
    public void updateTask(int taskId, int assignee_id, String title, String description, TaskStatus status) throws RemoteException {
        String sql = """
                UPDATE tasks
                SET title = ?, assignee_id = ?, description = ?, status = ?::task_status, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? RETURNING board_id
            """;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, title);
            stmt.setInt(2, assignee_id);
            stmt.setString(3, description);
            stmt.setString(4, status.name());
            stmt.setInt(5, taskId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int boardId = rs.getInt("board_id");
                notifyTaskUpdate(boardId);
            } else {
                throw new RemoteException("Failed to update task");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error updating task", e);
        }
    }

    @Override
    public void updateBoard(int boardId, String newName) throws RemoteException {
        String sql = "UPDATE boards SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newName);
            stmt.setInt(2, boardId);
            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                throw new RemoteException("Board not found or name could not be updated.");
            }

            notifyBoardListChanged();

        } catch (SQLException e) {
            e.printStackTrace();
            // Check for unique constraint violation (if the name must be unique)
            if (e.getSQLState().equals("23505")) { // PostgresSQL unique violation code
                throw new RemoteException("Board name '" + newName + "' already exists.", e);
            }
            throw new RemoteException("Error updating board name", e);
        }
    }

    @Override
    public void deleteBoard(int boardId) throws RemoteException {
        String sql = "DELETE FROM boards WHERE id = ? RETURNING id";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, boardId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int deletedBoardId = rs.getInt("id");
                System.out.println("Deleted board with ID: " + deletedBoardId);
                // Notify listeners about the deletion
                notifyBoardListChanged();
            } else {
                throw new RemoteException("Failed to delete board");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error deleting board", e);
        }
    }

    private void notifyTaskUpdate(int boardId) {
        System.out.println("Notifying " + listeners.size() + " listeners about update for board " + boardId);
        // Iterate over the thread-safe list
        for (ClientListener listener : listeners) {
            try {
                System.out.println("Notifying listener: " + listener);
                listener.onTasksUpdated(boardId);
            } catch (RemoteException e) {
                // Handle potential communication errors with a specific listener
                System.err.println("Failed to notify listener " + listener + ": " + e.getMessage());
                // Remove the listener if it's unreachable
                listeners.remove(listener);
                System.out.println("Removed unresponsive listener: " + listener);
            } catch (Exception e) {
                // Catch other potential exceptions during callback
                System.err.println("Error during listener callback for " + listener + ": " + e.getMessage());
                e.printStackTrace();
                // Optionally remove the listener if it causes persistent errors
                // listeners.remove(listener);
            }
        }
    }

    private void notifyBoardListChanged() {
        System.out.println("Notifying " + listeners.size() + " listeners about board list change.");
        for (ClientListener listener : listeners) {
            try {
                System.out.println("Notifying listener about board change: " + listener);
                listener.onBoardListChanged();
            } catch (RemoteException e) {
                System.err.println("Failed to notify listener " + listener + " about board change: " + e.getMessage());
                listeners.remove(listener);
                System.out.println("Removed unresponsive listener: " + listener);
            } catch (Exception e) {
                System.err.println("Error during board change listener callback for " + listener + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @Override
    public void sendMessage(int userId, String content) throws RemoteException {
        String sqlInsert = "INSERT INTO messages (user_id, content) VALUES (?, ?) RETURNING message_id, created_at";
        String sqlSelectUser = "SELECT username FROM users WHERE id = ?";
        ChatMessage newMessage = null;

        try (Connection conn = DatabaseManager.getConnection()) {
            // Start transaction
            conn.setAutoCommit(false);

            try (PreparedStatement stmtInsert = conn.prepareStatement(sqlInsert);
                 PreparedStatement stmtSelectUser = conn.prepareStatement(sqlSelectUser)) {

                // Insert the message
                stmtInsert.setInt(1, userId);
                stmtInsert.setString(2, content);
                ResultSet rsInsert = stmtInsert.executeQuery();

                int messageId = -1;
                LocalDateTime createdAt = null;
                if (rsInsert.next()) {
                    messageId = rsInsert.getInt("message_id");
                    createdAt = rsInsert.getTimestamp("created_at").toLocalDateTime();
                } else {
                    throw new SQLException("Failed to insert message, no ID obtained.");
                }

                // Get the username
                stmtSelectUser.setInt(1, userId);
                ResultSet rsUser = stmtSelectUser.executeQuery();
                String username = "Unknown"; // Default if user not found (shouldn't happen with FK)
                if (rsUser.next()) {
                    username = rsUser.getString("username");
                }

                // Create the ChatMessage object
                newMessage = new ChatMessage(messageId, userId, username, content, createdAt);

                // Commit transaction
                conn.commit();

            } catch (SQLException e) {
                conn.rollback(); // Rollback on error
                e.printStackTrace();
                throw new RemoteException("Error sending message", e);
            } finally {
                conn.setAutoCommit(true); // Restore default auto-commit behavior
            }

        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Database connection error while sending message", e);
        }

        // Notify listeners only if the message was successfully created and committed
        if (newMessage != null) {
            notifyChatListeners(newMessage);
        }
    }

    @Override
    public List<ChatMessage> getChatHistory() throws RemoteException {
        List<ChatMessage> history = new ArrayList<>();
        // Join messages with users to get the username
        String sql = """
            SELECT m.message_id, m.user_id, u.username, m.content, m.created_at
            FROM messages m
            JOIN users u ON m.user_id = u.id
            ORDER BY m.created_at ASC
            LIMIT 100
        """; // Added LIMIT to prevent loading excessive history

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                history.add(new ChatMessage(
                        rs.getInt("message_id"),
                        rs.getInt("user_id"),
                        rs.getString("username"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RemoteException("Error fetching chat history", e);
        }
        return history;
    }

    private void notifyChatListeners(ChatMessage message) {
        System.out.println("Notifying " + listeners.size() + " listeners about new chat message.");
        for (ClientListener listener : listeners) {
            try {
                System.out.println("Notifying listener about chat: " + listener);
                listener.onChatMessageReceived(message); // Send the whole object
            } catch (RemoteException e) {
                System.err.println("Failed to notify listener " + listener + " about chat: " + e.getMessage());
                listeners.remove(listener);
                System.out.println("Removed unresponsive listener: " + listener);
            } catch (Exception e) {
                System.err.println("Error during chat listener callback for " + listener + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
