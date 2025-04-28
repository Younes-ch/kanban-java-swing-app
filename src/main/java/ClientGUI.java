import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;

public class ClientGUI extends UnicastRemoteObject implements ClientListener {

    private KanbanService service;
    private JFrame frame;
    private DefaultListModel<Board> boardListModel;
    private JList<Board> boardList;
    private JPanel mainPanel;
    private JTable taskTable;
    private DefaultTableModel taskTableModel;
    private JTextArea chatDisplayArea;
    private JTextField chatInputField;
    private JButton sendButton;
    private int currentBoardId = -1;
    private User currentUser;
    private Map<Integer, String> userMap = new HashMap<>();

    private static final int COL_ID = 0;
    private static final int COL_TITLE = 1;
    private static final int COL_DESC = 2;
    private static final int COL_STATUS = 3;
    private static final int COL_CREATED_BY = 4;
    private static final int COL_ASSIGNED_TO = 5;
    private static final int COL_CREATED_AT = 6;
    private static final int COL_UPDATED_AT = 7;

    // Hidden columns
    private static final int COL_USER_ID = 8;
    private static final int COL_ASSIGNEE_ID = 9;

    private static final DateTimeFormatter TABLE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter CHAT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    public ClientGUI(KanbanService service) throws RemoteException{
        super();
        this.service = service;
    }

    private void fetchUsers() {
        try {
            List<User> users = service.getUsers();
            userMap.clear();
            for (User user : users) {
                userMap.put(user.getId(), user.getUsername());
            }
            System.out.println("Fetched " + userMap.size() + " users.");
        } catch (RemoteException e) {
            handleRemoteException("Error fetching users", e);
        }
    }

    public void launch() {
        // Show login dialog first
        if (!showLoginDialog()) {
            System.out.println("Login cancelled or failed. Exiting.");
            System.exit(0); // Exit if login is not successful
            return;
        }

        // If login is successful, proceed to initialize the main GUI
        initializeMainGUI();
    }

    private boolean showLoginDialog() {
        JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));
        JLabel userLabel = new JLabel("Username:");
        JTextField userField = new JTextField();
        JLabel passLabel = new JLabel("Password:");
        JPasswordField passField = new JPasswordField();

        panel.add(userLabel);
        panel.add(userField);
        panel.add(passLabel);
        panel.add(passField);

        // Set focus on the username field initially
        SwingUtilities.invokeLater(userField::requestFocusInWindow);

        int result = JOptionPane.showConfirmDialog(null, panel, "Login",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String username = userField.getText().trim();
            String password = new String(passField.getPassword());

            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Username and password cannot be empty.", "Login Error", JOptionPane.ERROR_MESSAGE);
                return showLoginDialog(); // Re-show dialog
            }

            try {
                User authenticatedUser = service.authenticateUser(username, password);
                if (authenticatedUser != null) {
                    this.currentUser = authenticatedUser;
                    System.out.println("Login successful for user: " + currentUser.getUsername() + " (ID: " + currentUser.getId() + ")");
                    return true; // Login successful
                } else {
                    JOptionPane.showMessageDialog(null, "Invalid username or password.", "Login Failed", JOptionPane.ERROR_MESSAGE);
                    return showLoginDialog(); // Re-show dialog on failure
                }
            } catch (RemoteException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Error connecting to server: " + e.getMessage(), "Login Error", JOptionPane.ERROR_MESSAGE);
                return false; // Exit on connection error
            }
        } else {
            return false; // User cancelled
        }
    }

    private void initializeMainGUI() {
        try {
            service.registerListener(this);
            fetchUsers();
        } catch (RemoteException e) {
            e.printStackTrace();
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(null, "Failed to register client listener: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
            );
            return;
        }

        frame = new JFrame("Planny App - Logged in as: " + currentUser.getUsername());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1500, 600);
        frame.setLayout(new BorderLayout(5, 5));

        // --- Menu Bar ---
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem saveAsCsvItem = new JMenuItem("Save As CSV...");
        saveAsCsvItem.addActionListener(e -> saveTableAsCsv());
        fileMenu.add(saveAsCsvItem);
        menuBar.add(fileMenu);
        frame.setJMenuBar(menuBar); // Set the menu bar for the frame

        // Sidebar: Board list
        boardListModel = new DefaultListModel<>();
        boardList = new JList<>(boardListModel);
        boardList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        boardList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleBoardPopupTrigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleBoardPopupTrigger(e);
            }

            private void handleBoardPopupTrigger(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int index = boardList.locationToIndex(e.getPoint());
                    if (index != -1) {
                        boardList.setSelectedIndex(index); // Select the board under the cursor
                        showBoardContextMenu(e, index);
                    }
                }
            }
        });

        JScrollPane boardScrollPane = new JScrollPane(boardList);
        boardScrollPane.setPreferredSize(new Dimension(200, 0));
        frame.add(boardScrollPane, BorderLayout.WEST);

        // Main panel: Tasks
        mainPanel = new JPanel(new BorderLayout());

        // Task table
        taskTableModel = new DefaultTableModel(new Object[]{"ID", "Title", "Description", "Status", "Created By", "Assigned To", "Created At", "Updated At", "UserId", "AssigneeId"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == COL_TITLE || column == COL_DESC;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == COL_CREATED_AT || columnIndex == COL_UPDATED_AT) {
                    return LocalDateTime.class;
                }
                return super.getColumnClass(columnIndex);
            }
        };
        taskTable = new JTable(taskTableModel);
        taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);


        taskTableModel.addTableModelListener(e -> {
            // Check if it's an UPDATE event and not the header row or a full table refresh
            if (e.getType() == TableModelEvent.UPDATE && e.getFirstRow() >= 0
                    && e.getColumn() != TableModelEvent.ALL_COLUMNS) {

                int row = e.getFirstRow();
                int column = e.getColumn();

                if (row < taskTableModel.getRowCount() && column < taskTableModel.getColumnCount()) {
                    int taskId = (int) taskTableModel.getValueAt(row, COL_ID);

                    String currentTitle = (String) taskTableModel.getValueAt(row, COL_TITLE);
                    String currentDescription = (String) taskTableModel.getValueAt(row, COL_DESC);
                    TaskStatus currentStatus = (TaskStatus) taskTableModel.getValueAt(row, COL_STATUS);
                    int currentAssignedId = (int) taskTableModel.getValueAt(row, COL_ASSIGNEE_ID);

                    if (column == COL_TITLE) {
                        if (currentTitle.trim().isEmpty()) {
                            JOptionPane.showMessageDialog(frame, "Task title cannot be empty.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                            loadTasksForBoard(currentBoardId); // Reload to revert change
                            return;
                        }
                        if (currentTitle.length() > 100) {
                            JOptionPane.showMessageDialog(frame, "Task title cannot exceed 100 characters.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                            loadTasksForBoard(currentBoardId); // Reload to revert change
                            return;
                        }
                    } else if (column == COL_DESC) {
                        if (currentDescription.length() > 500) {
                            JOptionPane.showMessageDialog(frame, "Task description cannot exceed 500 characters.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                            loadTasksForBoard(currentBoardId); // Reload to revert change
                            return;
                        }
                    }

                    // Call the service update method with all current fields
                    try {
                        if (column == COL_TITLE || column == COL_DESC) {
                            service.updateTask(taskId, currentAssignedId, currentTitle, currentDescription, currentStatus);
                        }

                    } catch (RemoteException ex) {
                        handleRemoteException("Failed to update task", ex);
                    } catch (ClassCastException castEx) {
                        System.err.println("Error casting table value during update: " + castEx.getMessage());
                        handleRemoteException("Internal error processing update", new RemoteException(castEx.getMessage()));
                    }
                }
            }
        });

        // --- Hide the User ID and Assignee ID columns ---
        TableColumnModel columnModel = taskTable.getColumnModel();
        hideColumn(columnModel, COL_USER_ID);
        hideColumn(columnModel, COL_ASSIGNEE_ID);

        // Set preferred widths for visible columns
        columnModel.getColumn(COL_ID).setPreferredWidth(50);
        columnModel.getColumn(COL_TITLE).setPreferredWidth(200);
        columnModel.getColumn(COL_DESC).setPreferredWidth(300);
        columnModel.getColumn(COL_STATUS).setPreferredWidth(100);
        columnModel.getColumn(COL_CREATED_BY).setPreferredWidth(100);
        columnModel.getColumn(COL_ASSIGNED_TO).setPreferredWidth(100);
        columnModel.getColumn(COL_CREATED_AT).setPreferredWidth(140);
        columnModel.getColumn(COL_UPDATED_AT).setPreferredWidth(140);

        TableCellRenderer statusRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                // Get the default component (JLabel)
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                if (value instanceof TaskStatus status) {
                    switch (status) {
                        case TO_DO:
                            c.setBackground(Color.RED);
                            c.setForeground(Color.WHITE); // Set text color for contrast
                            break;
                        case IN_PROGRESS:
                            c.setBackground(Color.YELLOW);
                            c.setForeground(Color.BLACK); // Set text color for contrast
                            break;
                        case DONE:
                            c.setBackground(Color.GREEN);
                            c.setForeground(Color.BLACK); // Set text color for contrast
                            break;
                        default:
                            // Reset to default if status is unknown (shouldn't happen)
                            c.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                            c.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                            break;
                    }
                    // Set text alignment to center for the status
                    ((JLabel) c).setHorizontalAlignment(SwingConstants.CENTER);
                } else {
                    // Reset to default colors if the value is not a TaskStatus or null
                    c.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                    c.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
                    ((JLabel) c).setHorizontalAlignment(SwingConstants.LEFT); // Reset alignment
                }

                // Ensure the text of the status is still displayed
                setText(value != null ? value.toString() : "");

                return c;
            }
        };
        // Apply the custom renderer to the Status column
        taskTable.getColumnModel().getColumn(COL_STATUS).setCellRenderer(statusRenderer);

        TableCellRenderer dateTimeRenderer = (table, value, isSelected, hasFocus, row, column) -> {
            if (value instanceof LocalDateTime) {
                return new JLabel(((LocalDateTime) value).format(TABLE_DATE_FORMATTER));
            }
            return new JLabel(value != null ? value.toString() : "");
        };
        taskTable.getColumnModel().getColumn(COL_CREATED_AT).setCellRenderer(dateTimeRenderer);
        taskTable.getColumnModel().getColumn(COL_UPDATED_AT).setCellRenderer(dateTimeRenderer);

        // --- Add KeyListener for a Delete key ---
        taskTable.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                    int selectedRow = taskTable.getSelectedRow();
                    if (selectedRow != -1) { // Check if a row is actually selected
                        int taskId = (int) taskTableModel.getValueAt(selectedRow, 0);
                        confirmAndDeleteTask(taskId); // Call the delete confirmation method
                    }
                }
            }
        });

        // --- MouseListener for Context Menu ---
        taskTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopupTrigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopupTrigger(e);
            }

            private void handlePopupTrigger(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = taskTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < taskTable.getRowCount()) {
                        taskTable.setRowSelectionInterval(row, row); // Select the row
                        showTaskContextMenu(e, row);
                    }
                }
            }
        });

        JScrollPane taskScrollPane = new JScrollPane(taskTable);

        mainPanel.add(taskScrollPane, BorderLayout.CENTER);
        frame.add(mainPanel, BorderLayout.CENTER);

        // --- Chat Panel (EAST) ---
        JPanel chatPanel = new JPanel(new BorderLayout(5, 5));
        chatPanel.setBorder(BorderFactory.createTitledBorder("Chat")); // Add a title border
        chatPanel.setPreferredSize(new Dimension(300, 0)); // Set preferred width

        chatDisplayArea = new JTextArea();
        chatDisplayArea.setEditable(false); // Users shouldn't edit the history
        chatDisplayArea.setLineWrap(true);
        chatDisplayArea.setWrapStyleWord(true);
        JScrollPane chatScrollPane = new JScrollPane(chatDisplayArea);
        chatPanel.add(chatScrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(5, 0)); // Panel for input field and button
        chatInputField = new JTextField();
        sendButton = new JButton("Send");
        inputPanel.add(chatInputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        chatPanel.add(inputPanel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> {
            sendMessage();
        });

        chatInputField.addActionListener(e -> sendMessage());

        frame.add(chatPanel, BorderLayout.EAST); // Add chat panel to the frame

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        JButton addBoardButton = new JButton("➕ New Board");
        addBoardButton.addActionListener(e -> openNewBoardDialog());
        buttonPanel.add(addBoardButton);

        JButton newTaskButton = new JButton("➕ New Task");
        newTaskButton.addActionListener(e -> openNewTaskDialog());
        buttonPanel.add(newTaskButton);

        frame.add(buttonPanel, BorderLayout.SOUTH);

        // Fetch boards
        fetchBoards();
        loadChatHistory();

        // Listener for selecting boards
        boardList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Board selectedBoard = boardList.getSelectedValue();
                if (selectedBoard != null) {
                    showBoardTasks(selectedBoard);
                    currentBoardId = selectedBoard.getId();
                } else {
                    currentBoardId = -1;
                    loadTasksForBoard(currentBoardId); // Clear tasks if no board selected
                }
            }
        });

        frame.setVisible(true);
    }

    private void saveTableAsCsv() {
        if (currentBoardId == -1) {
            JOptionPane.showMessageDialog(frame, "Please select a board first.", "Cannot Save", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Board selectedBoard = boardList.getSelectedValue();
        if (selectedBoard == null) {
            // This case should ideally not happen if currentBoardId != -1, but good to check
            JOptionPane.showMessageDialog(frame, "No board selected.", "Cannot Save", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String boardName = selectedBoard.getName().replaceAll("[^a-zA-Z0-9\\-_]", "_"); // Sanitize board name for filename

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Task Table as CSV");
        // Suggest a filename based on the board name
        fileChooser.setSelectedFile(new File(boardName + "_tasks.csv"));

        int userSelection = fileChooser.showSaveDialog(frame);

        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            // Ensure the file has a .csv extension
            if (!fileToSave.getName().toLowerCase().endsWith(".csv")) {
                fileToSave = new File(fileToSave.getParentFile(), fileToSave.getName() + ".csv");
            }

            System.out.println("Saving table to: " + fileToSave.getAbsolutePath());

            try (FileWriter fw = new FileWriter(fileToSave);
                 BufferedWriter bw = new BufferedWriter(fw)) {

                TableColumnModel colModel = taskTable.getColumnModel();
                List<Integer> visibleColumns = new ArrayList<>();
                List<String> headers = new ArrayList<>();

                // Get visible column indices and headers
                for (int i = 0; i < taskTableModel.getColumnCount(); i++) {
                    // Check if the column is visible (basic check, might need refinement if columns are hidden differently)
                    if (colModel.getColumn(i).getMaxWidth() > 0) {
                        visibleColumns.add(i);
                        headers.add(taskTableModel.getColumnName(i));
                    }
                }

                // Write header row
                bw.write(String.join(",", headers));
                bw.newLine();

                // Write data rows
                for (int row = 0; row < taskTableModel.getRowCount(); row++) {
                    List<String> rowData = new ArrayList<>();
                    for (int colIndex : visibleColumns) {
                        Object value = taskTableModel.getValueAt(row, colIndex);
                        String cellValue;
                        if (value instanceof LocalDateTime) {
                            // Format LocalDateTime using the table formatter
                            cellValue = ((LocalDateTime) value).format(TABLE_DATE_FORMATTER);
                        } else {
                            cellValue = (value == null) ? "" : value.toString();
                        }
                        // Basic CSV escaping: double quotes around values containing commas or quotes
                        if (cellValue.contains(",") || cellValue.contains("\"") || cellValue.contains("\n")) {
                            cellValue = "\"" + cellValue.replace("\"", "\"\"") + "\"";
                        }
                        rowData.add(cellValue);
                    }
                    bw.write(String.join(",", rowData));
                    bw.newLine();
                }

                JOptionPane.showMessageDialog(frame, "Table saved successfully to:\n" + fileToSave.getName(), "Save Successful", JOptionPane.INFORMATION_MESSAGE);

            } catch (IOException ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Error saving file: " + ex.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void sendMessage() {
        String messageText = chatInputField.getText().trim();
        if (!messageText.isEmpty()) {
            try {
                // Call service with userId and content
                service.sendMessage(currentUser.getId(), messageText);
                chatInputField.setText(""); // Clear the input field
            } catch (RemoteException e) {
                handleRemoteException("Failed to send message", e);
            }
        }
        chatInputField.requestFocusInWindow(); // Keep focus on input field
    }

    private void loadChatHistory() {
        // Start a new background thread to fetch history
        new Thread(() -> {
            try {
                // Perform the potentially blocking RMI call in the background
                List<ChatMessage> history = service.getChatHistory();

                // Update the UI on the Event Dispatch Thread
                SwingUtilities.invokeLater(() -> {
                    chatDisplayArea.setText(""); // Clear existing content
                    for (ChatMessage message : history) {
                        appendChatMessage(message); // Use helper to format
                    }
                    // Scroll to bottom after loading history
                    chatDisplayArea.setCaretPosition(chatDisplayArea.getDocument().getLength());
                });

            } catch (RemoteException e) {
                // Handle exceptions, ensuring error messages are shown on the EDT
                handleRemoteException("Failed to load chat history", e);
            } catch (Exception ex) {
                // Catch any other unexpected exceptions during background processing
                ex.printStackTrace();
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame, "An unexpected error occurred while loading chat history: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                );
            }
        }).start(); // Start the background thread
    }

    private void appendChatMessage(ChatMessage message) {
        String formattedTime = message.getCreatedAt().format(CHAT_DATE_FORMATTER);
        String formattedMessage = String.format("[%s] %s: %s\n",
                formattedTime,
                message.getUsername(),
                message.getContent());
        chatDisplayArea.append(formattedMessage);
    }

    // Helper method to hide a column
    private void hideColumn(TableColumnModel columnModel, int columnIndex) {
        TableColumn column = columnModel.getColumn(columnIndex);
        column.setMinWidth(0);
        column.setMaxWidth(0);
        column.setPreferredWidth(0);
    }


    private void showBoardContextMenu(MouseEvent e, int index) {
        Board selectedBoard = boardListModel.getElementAt(index);
        if (selectedBoard == null) return;

        JPopupMenu contextMenu = new JPopupMenu();

        // --- "Rename Board" Option ---
        JMenuItem renameItem = new JMenuItem("Rename Board");
        renameItem.addActionListener(actionEvent -> {
            renameBoard(selectedBoard);
        });
        contextMenu.add(renameItem);

        // --- Add "Delete Board" Option (Optional) ---
        JMenuItem deleteBoardItem = new JMenuItem("Delete Board");
        deleteBoardItem.addActionListener(actionEvent -> {
        // Implement delete board confirmation and service call here
            int confirm = JOptionPane.showConfirmDialog(frame,
                    "Are you sure you want to delete board \"" + selectedBoard.getName() + "\"?",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    service.deleteBoard(selectedBoard.getId());
                } catch (RemoteException ex) {
                    handleRemoteException("Failed to delete board", ex);
                }
            }
        });
        contextMenu.add(deleteBoardItem);

        contextMenu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void renameBoard(Board board) {
        String currentName = board.getName();
        String newName = JOptionPane.showInputDialog(frame, "Enter new name for board:", currentName);

        if (newName != null) { // User didn't cancel
            newName = newName.trim();
            if (newName.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Board name cannot be empty.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (newName.equals(currentName)) {
                return; // No change needed
            }

            try {
                service.updateBoard(board.getId(), newName);
            } catch (RemoteException ex) {
                handleRemoteException("Failed to rename board", ex);
            }
        }
    }

    private void confirmAndDeleteTask(int taskId) {
        int confirm = JOptionPane.showConfirmDialog(frame,
                "Are you sure you want to delete task ID " + taskId + "?",
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            try {
                service.deleteTask(taskId);
                // Table will be refreshed by onTasksUpdated callback
            } catch (RemoteException ex) {
                handleRemoteException("Failed to delete task", ex);
            }
        }
    }

    // --- Helper method for handling RemoteExceptions ---
    private void handleRemoteException(String messagePrefix, RemoteException ex) {
        ex.printStackTrace();
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(frame, messagePrefix + ": " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    private void showTaskContextMenu(MouseEvent e, int row) {
        JPopupMenu contextMenu = new JPopupMenu();

        int taskId = (int) taskTableModel.getValueAt(row, COL_ID);
        String currentTitle = (String) taskTableModel.getValueAt(row, COL_TITLE);
        String currentDescription = (String) taskTableModel.getValueAt(row, COL_DESC);
        TaskStatus currentStatus = (TaskStatus) taskTableModel.getValueAt(row, COL_STATUS);
        int currentAssigneeId = (int) taskTableModel.getValueAt(row, COL_ASSIGNEE_ID);

        // --- "Reassign Task" Option ---
        JMenu reassignToMenu = new JMenu("Reassign to");
        boolean addedReassignOption = false;
        // Loop through the userMap to find users
        for (Map.Entry<Integer, String> entry : userMap.entrySet()) {
            if (entry.getKey() != currentAssigneeId) { // Only show other users
                JMenuItem userItem = new JMenuItem(entry.getValue());
                userItem.addActionListener(actionEvent -> {
                    try {
                        service.updateTask(taskId, entry.getKey(), currentTitle, currentDescription, currentStatus);
                    } catch (RemoteException ex) {
                        ex.printStackTrace();
                        SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(frame, "Failed to move task: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                        );
                    }
                });
                reassignToMenu.add(userItem);
                addedReassignOption = true;
            }
        }
        // Only add the "Reassign to" menu if there are options
        if (addedReassignOption) {
            contextMenu.add(reassignToMenu);
        }

        // --- "Move to" Submenu ---
        JMenu moveToMenu = new JMenu("Move to");
        for (TaskStatus newStatus : TaskStatus.values()) {
            if (newStatus != currentStatus) { // Only show other statuses
                JMenuItem statusItem = new JMenuItem(newStatus.toString().replace('_', ' ')); // Make it more readable
                statusItem.addActionListener(actionEvent -> {
                    try {
                        service.moveTask(taskId, newStatus);
                        // Table will be refreshed by onTasksUpdated callback
                    } catch (RemoteException ex) {
                        ex.printStackTrace();
                        SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(frame, "Failed to move task: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                        );
                    }
                });
                moveToMenu.add(statusItem);
            }
        }

        contextMenu.add(moveToMenu);

        // --- "Delete" Option ---
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.addActionListener(actionEvent -> {
            int confirm = JOptionPane.showConfirmDialog(frame,
                    "Are you sure you want to delete task ID " + taskId + "?",
                    "Confirm Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    service.deleteTask(taskId);
                    // Table will be refreshed by onTasksUpdated callback
                } catch (RemoteException ex) {
                    ex.printStackTrace();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(frame, "Failed to delete task: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
                    );
                }
            }
        });
        contextMenu.add(deleteItem);

        // Show the menu at the mouse-click location
        contextMenu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void fetchBoards() {
        Board previouslySelected = boardList.getSelectedValue(); // Remember selection
        try {
            List<Board> boards = service.getBoards();
            boardListModel.clear();
            Board toReselect = null;
            for (Board board : boards) {
                boardListModel.addElement(board);
                // Check if this is the board that was previously selected (by ID)
                if (previouslySelected != null && board.getId() == previouslySelected.getId()) {
                    toReselect = board;
                }
            }
            // Re-select the board if it still exists
            if (toReselect != null) {
                boardList.setSelectedValue(toReselect, true); // Scroll to make it visible
            }
        } catch (RemoteException e) {
            handleRemoteException("Error fetching boards", e); // Use helper
        }
    }

    private void showBoardTasks(Board board) {
        if (board == null) {
            loadTasksForBoard(-1); // Clear tasks if board is null
            return;
        }
        loadTasksForBoard(board.getId());
    }

    private void openNewBoardDialog() {
        JTextField nameField = new JTextField();
        JPanel panel = new JPanel(new GridLayout(2, 1));
        panel.add(new JLabel("Board Name:"));
        panel.add(nameField);

        int result = JOptionPane.showConfirmDialog(frame, panel, "Create New Board", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String boardName = nameField.getText().trim();
            if (boardName.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Board name cannot be empty.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                service.createBoard(boardName);
            } catch (RemoteException ex) {
                handleRemoteException("Failed to create board", ex);
            }
        }
    }

private void openNewTaskDialog() {
    if (currentBoardId == -1) {
        JOptionPane.showMessageDialog(frame, "Please select a board first!", "No Board Selected", JOptionPane.WARNING_MESSAGE);
        return;
    }

    JTextField titleField = new JTextField();
    JTextArea descriptionField = new JTextArea(5, 20);
    descriptionField.setLineWrap(true);
    descriptionField.setWrapStyleWord(true);
    JScrollPane descriptionScrollPane = new JScrollPane(descriptionField);
    JComboBox<TaskStatus> statusCombo = new JComboBox<>(TaskStatus.values());

    JComboBox<String> assigneeCombo = new JComboBox<>();
    Map<String, Integer> assigneeNameToIdMap = new HashMap<>();

    for (Map.Entry<Integer, String> entry : userMap.entrySet()) {
        assigneeCombo.addItem(entry.getValue());
        assigneeNameToIdMap.put(entry.getValue(), entry.getKey());
    }

    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridwidth = GridBagConstraints.REMAINDER;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    gbc.insets = new Insets(2, 2, 2, 2);

    panel.add(new JLabel("Title:"), gbc);
    panel.add(titleField, gbc);

    gbc.weighty = 1.0; // Allow vertical expansion for the text area
    gbc.fill = GridBagConstraints.BOTH; // Allow both horizontal and vertical expansion

    panel.add(new JLabel("Description:"), gbc);
    panel.add(descriptionScrollPane, gbc); // Add the scroll pane instead of the text area directly

    gbc.weighty = 0.0; // Reset vertical expansion
    gbc.fill = GridBagConstraints.HORIZONTAL; // Reset fill

    panel.add(new JLabel("Status:"), gbc);
    panel.add(statusCombo, gbc);

    panel.add(new JLabel("Assign to:"), gbc); // Add Assignee label
    panel.add(assigneeCombo, gbc);          // Add Assignee dropdown

    // Set the preferred size for the dialog panel if needed
    panel.setPreferredSize(new Dimension(350, 300)); // Increased height for assignee

    int result = JOptionPane.showConfirmDialog(frame, panel, "Create New Task", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

    if (result == JOptionPane.OK_OPTION) {
        String title = titleField.getText().trim();
        String description = descriptionField.getText().trim();
        TaskStatus status = (TaskStatus) statusCombo.getSelectedItem();
        String selectedAssigneeName = (String) assigneeCombo.getSelectedItem();
        int assigneeId = assigneeNameToIdMap.getOrDefault(selectedAssigneeName, currentUser.getId());

        if (title.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Task title cannot be empty.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (title.length() > 100) {
            JOptionPane.showMessageDialog(frame, "Task title cannot exceed 100 characters.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (description.length() > 500) {
            JOptionPane.showMessageDialog(frame, "Task description cannot exceed 500 characters.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            service.createTask(currentBoardId, currentUser.getId(), assigneeId, title, description, status);
        } catch (RemoteException ex) {
            handleRemoteException("Failed to create task", ex);
        } catch (Exception ex) { // Catch other potential exceptions
            ex.printStackTrace();
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(frame, "An unexpected error occurred while creating the task: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)
            );
        }
    }
}

    private void loadTasksForBoard(int boardId) {
        if (boardId == -1) {
            // Clear the table if no board is selected
            SwingUtilities.invokeLater(() -> {
                if (taskTable.isEditing()) {
                    taskTable.getCellEditor().cancelCellEditing();
                }
                taskTableModel.setRowCount(0);
            });
            return;
        }

        try {
            List<Task> tasks = service.getTasksByBoard(boardId);

            SwingUtilities.invokeLater(() -> {
                // Stop editing before reloading data to avoid conflicts
                if (taskTable.isEditing()) {
                    taskTable.getCellEditor().cancelCellEditing();
                }

                taskTableModel.setRowCount(0); // Clear existing tasks
                for (Task task : tasks) {
                    taskTableModel.addRow(new Object[]{
                            task.getId(),
                            task.getTitle(),
                            task.getDescription(),
                            task.getStatus(),
                            userMap.getOrDefault(task.getUserId(), "Unknown User"),
                            userMap.getOrDefault(task.getAssigneeId(), "Unassigned"),
                            task.getCreatedAt(),
                            task.getUpdatedAt(),
                            task.getUserId(),
                            task.getAssigneeId()
                    });
                }
            });

        } catch (RemoteException e) {
            // Show error messages also on the EDT
            handleRemoteException("Error loading tasks", e);
        }
    }

    @Override
    public void onTasksUpdated(int boardId) throws RemoteException {
        if (currentBoardId == boardId) {
            SwingUtilities.invokeLater(() -> loadTasksForBoard(boardId));
        }
    }

    @Override
    public void onBoardListChanged() throws RemoteException {
        SwingUtilities.invokeLater(this::fetchBoards);
    }

    @Override
    public void onChatMessageReceived(ChatMessage message) throws RemoteException {
        SwingUtilities.invokeLater(() -> {
            appendChatMessage(message); // Use helper to format
            // Auto-scroll to the bottom on new message
            chatDisplayArea.setCaretPosition(chatDisplayArea.getDocument().getLength());
        });
    }
}