import java.rmi.registry.LocateRegistry;
import java.rmi.Naming;

public class Server {

    public static void main(String[] args) {
        try {
            // 1. Initialize database connection and create tables
            DatabaseManager.initialize();

            // 2. Create the KanbanService implementation
            KanbanServiceImpl service = new KanbanServiceImpl();

            // 3. Start the RMI registry (on port 1099, default RMI port)
            try {
                LocateRegistry.createRegistry(1099);
                System.out.println("RMI Registry started on port 1099.");
            } catch (Exception e) {
                System.out.println("RMI Registry already running.");
            }

            // 4. Bind the service instance to the RMI registry
            Naming.rebind("KanbanService", service);
            System.out.println("KanbanService is ready!");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}