import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface KanbanService extends Remote {

    User authenticateUser(String username, String password) throws RemoteException;
    boolean createUser(String username, String password) throws RemoteException;
    List<User> getUsers() throws RemoteException;

    List<Board> getBoards() throws RemoteException;
    Board createBoard(String name) throws RemoteException;

    List<Task> getTasks(int boardId) throws RemoteException;
    List<Task> getTasksByBoard(int boardId) throws RemoteException;
    void createTask(int boardId, int user_id, int assignee_id, String title, String description, TaskStatus status) throws RemoteException;
    void moveTask(int taskId, TaskStatus newStatus) throws RemoteException;
    void deleteTask(int taskId) throws RemoteException;
    void updateTask(int taskId, int assignee_id, String title, String description, TaskStatus status) throws RemoteException;
    void updateBoard(int boardId, String name) throws RemoteException;
    void deleteBoard(int boardId) throws RemoteException;
    void sendMessage(int userId, String content) throws RemoteException;
    List<ChatMessage> getChatHistory() throws RemoteException;
    void registerListener(ClientListener listener) throws RemoteException;
    void unregisterListener(ClientListener listener) throws RemoteException;

}
