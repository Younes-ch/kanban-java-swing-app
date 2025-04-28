import java.rmi.Naming;
import java.rmi.RemoteException;

public class Client {

    private static KanbanService service;

    public static void main(String[] args) {
        try {
            // 1. Connect to the RMI Registry and look up the KanbanService
            service = (KanbanService) Naming.lookup("rmi://localhost/KanbanService");
            System.out.println("Connected to KanbanService!");

            // 2. Launch the GUI, passing the service instance
            ClientGUI clientGUI = new ClientGUI(service);
            clientGUI.launch();

        } catch (RemoteException e) { // Catch RemoteException from ClientGUI constructor
            System.err.println("ClientGUI RemoteException: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) { // Catch other exceptions (e.g., Naming lookup)
            System.err.println("Client Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
