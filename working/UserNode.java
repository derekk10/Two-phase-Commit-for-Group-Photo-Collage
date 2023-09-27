import java.io.*;
import java.util.*;
import java.rmi.RemoteException;

public class UserNode implements ProjectLib.MessageHandling {
    static ProjectLib PL;
    public final String myId;
    private static final int PREPARE = 1;
    private static final int COMMIT = 2;
    private static final int ABORT = -1;
    private static final int ACK = 3;
    //lockList keeps track of files that are locked
    public static LinkedList<String> lockList = new LinkedList<String>();
    public UserNode( String id ) {
        myId = id;
    }

    /**
     * @brief serialize() serializes an object into a byte array so that it can 
     * be sent to the server
     * @param obj takes an Object
     * @return outputs serialized object in form of byte array
     */
    public static byte[] serialize(Object obj) throws IOException{
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
        objectOutputStream.writeObject(obj);
        byte[] output = outputStream.toByteArray();
        return output;
    }

    /**
     * @brief deserialize() deserializes a byte array into an object
     * @param input takes a byte[]
     * @return returns object from byte[] input
     */
    public static Object deserialize(byte[] input) throws IOException, ClassNotFoundException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
        ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
        Object obj = objectInputStream.readObject();
        return obj;
    }

    /**
     * @brief takes an object with metadata about userNode state and logs it 
     * using a file output stream and the project library function fsync()
     * @param obj takes Object
     */
    public void logState(Object state) {
        try {
            RandomAccessFile raf = new RandomAccessFile("userState.log", "rw");
            FileOutputStream fileOutputStr = new FileOutputStream(raf.getFD());
            ObjectOutputStream objOutputStr = new ObjectOutputStream(fileOutputStr);
            objOutputStr.writeObject(state);
            objOutputStr.close();
            raf.close();
            PL.fsync();

        }
        catch(IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @brief recovery routine when userNode crashes. Reads log and resumes 
     * from where it crashed based on the phase it was in.
     */
    public static void recoverState() {
        try {
            FileInputStream fileInputStr = new FileInputStream("userState.log");
            ObjectInputStream objInputStr = new ObjectInputStream(fileInputStr);
            state userNodeState = (state)objInputStr.readObject();
            //information is retreived from the log file
            int decision = userNodeState.decision;
            int phase = userNodeState.phase;
            String filename = userNodeState.filename;
            String[] sources = userNodeState.sources;
            lock(userNodeState.sources);
            messageWrapper msgwrap;
            ProjectLib.Message msg;
            switch (phase) {
                case PREPARE: 
                    msgwrap = new messageWrapper(decision, null, "Server", sources, filename);
                    msg = new ProjectLib.Message("Server", serialize(msgwrap));
                    PL.sendMessage(msg);
                case COMMIT: 
                    //delete image
                    deleteFile(sources);
                    //unlock resources
                    unlock(sources);
                    //send ack to server
                    msgwrap = new messageWrapper(ACK, null, "Server", sources, filename);
                    msg = new ProjectLib.Message("Server", serialize(msgwrap));
                    PL.sendMessage(msg);
                    break;
                case ABORT:
                    //unlock resources
                    unlock(sources);
                    //send ack to server
                    msgwrap = new messageWrapper(ACK, null, "Server", sources, filename);
                    msg = new ProjectLib.Message("Server", serialize(msgwrap));
                    PL.sendMessage(msg);
                    break;
            }
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * @brief deliverMessage() receives a message from the server and sends an
     * appropriate response back. If in PREPARE phase, makes necessary checks, 
     * locks resources, asks user for vote and then logs and sends decision to 
     * server. If userNode receives decision (Commit or Abort), it unlocks or 
     * deletes resources and sends acknowledgement to server.
     * @param message takes a ProjectLib.Message
     * @return returns true if message is received and delivered correctly
     */
    public boolean deliverMessage( ProjectLib.Message message ) {
        System.out.println( myId + ": Got message from " + message.addr );
        try {
            messageWrapper msgwrap = (messageWrapper)deserialize(message.body);
            ProjectLib.Message msg;
            switch(msgwrap.opcode) {
                case PREPARE:
                    int decision;
                    //check for image and check whether sources are locked
                    if (!checkForImage(msgwrap.sources) || isLocked(msgwrap.sources)) {
                        msgwrap = new messageWrapper(ABORT, null, "Server", msgwrap.sources, msgwrap.filename);
                        msg = new ProjectLib.Message("Server", serialize(msgwrap));
                        break;
                    }
                    //lock resources
                    lock(msgwrap.sources);
                    //ask user and send decision to server
                    ProjectLib.Message imgMsg = msgwrap.msg;
                    boolean vote = PL.askUser(serialize(imgMsg.body), msgwrap.sources);
                    if (vote) {
                        decision = COMMIT;
                        state userNodeState = new state(msgwrap.filename, decision, msgwrap.sources, PREPARE);
                        logState(userNodeState);
                    } else {
                        decision = ABORT;
                    }
                    msgwrap = new messageWrapper(decision, null, "Server", msgwrap.sources, msgwrap.filename);
                    msg = new ProjectLib.Message("Server", serialize(msgwrap));
                    PL.sendMessage(msg);
                    break;
                case COMMIT:
                    //delete image
                    deleteFile(msgwrap.sources);
                    //unlock resources
                    unlock(msgwrap.sources);
                    //send ack to server
                    msgwrap = new messageWrapper(ACK, null, "Server", msgwrap.sources, msgwrap.filename);
                    msg = new ProjectLib.Message("Server", serialize(msgwrap));
                    PL.sendMessage(msg);
                    break;
                case ABORT:
                    //unlock resources
                    unlock(msgwrap.sources);
                    msgwrap = new messageWrapper(ACK, null, "Server", msgwrap.sources, msgwrap.filename);
                    msg = new ProjectLib.Message("Server", serialize(msgwrap));
                    PL.sendMessage(msg);
                    break;
            }
        }
        catch(IOException e) {
            e.printStackTrace();
        }
        catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * @brief given a String array of sources, checkForImage() checks whether
     * the file exists in the userNode
     * @param sources takes String[]
     * @return returns boolean indicating whether images exist on user side
     */
    public static boolean checkForImage(String[] sources) {
        for (String source : sources) {
            File file = new File(source);
            if (!file.exists()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @brief given a String array of sources, deleteImage() checks whether
     * the file exists in the userNode and deletes it if it exists
     * @param sources takes String[]
     */
    public static void deleteFile(String[] sources) {
        for (String source: sources) {
            File file = new File(source);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    /**
     * @brief given a String array of sources, unlock() checks whether the file
     * is in the list of locked sources and if it is locked, it removes it from
     * the list
     * @param sources takes String[]
     */
    public static void unlock(String[] sources) {
        for (String source : sources) {
            File file = new File(source);
            if (file.exists()) {
                lockList.remove(source);
            }
        }
    }
    
    /**
     * @brief given a String array of sources, lock() checks whether each file
     * exists on the userNode and locks it (adds it to list of locked sources)
     * @param sources takes String[]
     */
    public static void lock(String[] sources) {
        for (String source : sources) {
            File file = new File(source);
            if (file.exists()) {
                lockList.add(source);
            }
        }
    }

    /**
     * @brief given a String array of sources, isLocked() checks whether each 
     * is locked (is in the list of locked sources) and returns true if at least
     * one of the sources is locked and false otherwise
     * @param sources
     * @return returns boolean indicating whether sources are locked
     */
    public static boolean isLocked(String[] sources) {
        for (String source : sources) {
            if (lockList.contains(source)) {
                return true;
            }
        }
        return false;
    }


    public static void main ( String args[] ) throws Exception {
        if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
        UserNode UN = new UserNode(args[1]);
        PL = new ProjectLib( Integer.parseInt(args[0]), args[1], UN );
        ProjectLib.Message msg = PL.getMessage();
        File file = new File("userState.log");
        if (file.exists()) {
            recoverState();
        }
    }
}

