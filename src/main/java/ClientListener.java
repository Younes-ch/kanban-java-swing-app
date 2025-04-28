import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ClientListener extends Remote {
    void onTasksUpdated(int boardId) throws RemoteException;
    void onBoardListChanged() throws RemoteException;
    void onChatMessageReceived(ChatMessage message) throws RemoteException;
}
