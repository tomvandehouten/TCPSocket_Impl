import java.net.*;
import java.io.*;
import java.util.Timer;
import java.util.Random;
import java.util.Hashtable;


class StudentSocketImpl extends BaseSocketImpl {

    // SocketImpl data members:
    //   protected InetAddress address;
    //   protected int port;
    //   protected int localport;
    private enum State{
        CLOSED, LISTEN, SYN_RCVD, SYN_SENT, FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, TIME_WAIT, LAST_ACK, ESTABLISHED, CLOSING
    }
    
    State state;
    
    private Hashtable stateTable = new Hashtable();
    private Hashtable timerTable = new Hashtable();
    private int closedFlag = 0;
    private boolean waitingFlag = false;
    private Demultiplexer D;
    private Timer tcpTimer;
    private int clientSeqNumAcked, clientSeqNumNacked;
    private int serverSeqNumAcked, serverSeqNumNacked;
    
    private Boolean isServer = new Boolean(false);
    private Boolean isClient = new Boolean(false);
    
    private boolean isRetransmit = false;
    
    private boolean clientWait = true;
    private boolean serverWait = true;
    
    private boolean clientClosed = false;
    private boolean serverClosed = false;
    
    private boolean establishedFlag = false;
    private boolean timeWaitFlag = false;
    private boolean closingFlag = false;
    
    private boolean killClose = false;
    private InetAddress serverName;
    private int rp;
    
    StudentSocketImpl(Demultiplexer D) {  // default constructor
        this.D = D;
        this.state = State.CLOSED;
        this.localport = -1;
        //System.out.println("!!! CLOSED");
        clientSeqNumAcked = -1;
        clientSeqNumNacked = -1;
        serverSeqNumAcked = -1;
        serverSeqNumNacked = -1;

    }

    /**
    * Connects this socket to the specified port number on the specified host.
    *
    * @param      address   the IP address of the remote host.
    * @param      port      the port number.
    * @exception  IOException  if an I/O error occurs when attempting a
    *               connection.
    */
    public synchronized void connect(InetAddress address, int remotePort) throws IOException{
        /*CLIENT CODE*/
        this.isClient = new Boolean (true);
        
        if (this.localport == -1){
            this.address = address;
            this.port = remotePort;
            this.localport = D.getNextAvailablePort();
            
            serverName = address;
            rp = remotePort; 
            //System.out.println"StudentSocketImpl: connect: port is " + remotePort);
            //System.out.println"StudentSocketImpl: connect: localport is " + localport);
            
            //InetAddress address = InetAddress.getByName("ren.cs.wm.edu");
            //System.out.println"StudentSocketImpl: connect: Host name: " + address.getHostName());
            //System.out.println"StudentSocketImpl: connect: IP Address: " + address.getHostAddress());
            
            clientSeqNumNacked = makeRandom();
            clientSeqNumAcked = clientSeqNumNacked;
            D.registerConnection(address, localport, remotePort, this);
            
            
        }
        
        //System.out.println"isRetransmit is " + isRetransmit);
        if (!isRetransmit){
            clientSeqNumNacked++;
        }
        
        TCPPacket synPacket = new TCPPacket(localport, remotePort, clientSeqNumNacked, 0, false, true, false, 10, null);
        //TCPWrapper.send(synPacket, this.address);
        sendTimedPacket(synPacket);
        transitionState(this.state, State.SYN_SENT);
        
        while(this.state != State.ESTABLISHED){
            try{
                this.wait();
            }
            catch(Exception e){
            
            }
        }
        
    }

    /**
    * Called by Demultiplexer when a packet comes in for this connection
    * @param p The packet that arrived
    */
    public synchronized void receivePacket(TCPPacket p){
        //System.out.println"StudentSocketImpl: receivePacket " + p);
        //increaseNacked();
        
        
        // byte[] packetBuffer = p.getBufferPacket();
        TCPPacket ackPacket;
        switch(this.state){
            
            case LISTEN:
                if (p.synFlag && p.ackFlag){ // SERVER CODE IF TIMER WENT OFF
                    //System.out.println"LISTEN: Synack");
                    //p.seqNum = serverNumNacked;
                    increaseNacked();
                    sendTimedPacket(p);
                    transitionState(this.state, State.SYN_RCVD);
                    try{
                        //System.out.println"NOTIFY ALL, LISTEN SERVER seqNum is " + p.seqNum);
                        this.notifyAll();
                    }
                    catch(Exception e){
                        //System.out.println"SERVER: LISTEN: notify catch");
                    }
                }
                
                else if (p.synFlag){ //Event: recv SYN
                                //Response: send SYN+ACK
                    //SERVER CODE 
                    //System.out.println"LISTEN: Syn flag");
                    this.address = p.sourceAddr;
                    this.port = p.sourcePort;
                    
                    
                    while (Math.abs(serverSeqNumNacked - p.seqNum) <= 100){
                        serverSeqNumNacked = makeRandom();
                    }
                    
                    increaseNacked();
                    TCPPacket synAckPacket = new TCPPacket(this.localport, this.port, serverSeqNumNacked, p.seqNum, true, true, false, 10, null);
                    //TCPWrapper.send(synAckPacket, this.address);
                    
                    sendTimedPacket(synAckPacket);
                    //T-Mute: System.out.println("SERVER: LISTEN: sent synAckPacket");
                    
                    try{
                        //T-Mute: System.out.println("SERVER: localport is " + localport);
                        //T-Mute: System.out.println("SERVER: address is " + address);
                        //T-Mute: System.out.println("SERVER: port is " + port);
                        D.unregisterListeningSocket(this.localport, this);
                        D.registerConnection(this.address, this.localport, this.port, this);
                    }
                    catch(Exception e){
                        //System.out.println"SERVER: LISTEN: register catch");
                    }
                    //System.out.println"SERVER: LISTEN: got past unregister and reregister sockets");
                    transitionState(this.state, State.SYN_RCVD);
                    try{
                        //System.out.println"NOTIFY ALL, LISTEN seqNum is " + p.seqNum);
                        this.notifyAll();
                    }
                    catch(Exception e){
                        //System.out.println"SERVER: LISTEN: notify catch");
                    }
                    //T-Mute: System.out.println("SERVER: LISTEN: got past notifyAll");
                }
                else{
                    //System.out.println"LISTEN: no syn or synack");
                }
                
                break;
            case SYN_SENT:
                if (p.synFlag && p.ackFlag){ 
                    // CLIENT CODE       
                    //System.out.println"SYN_SENT: synack");
                    
                    if (timerTable.get(p.ackNum) != null){
                        cancelTimer(p.ackNum);
                    }
                    else{
                        //System.out.println"could not find packet with ackNum " + p.ackNum + " in timer Table");
                    }
                    clientSeqNumAcked = p.seqNum;
                    
                    //System.out.println"close: clientSeqNumNacked before is " + clientSeqNumNacked);
                    increaseNacked();
                    //System.out.println"close: clientSeqNumNacked after is " + clientSeqNumNacked);
                    
                
                    
                    //Remember numNacked does not increment for duplicate packets
                    ackPacket = new TCPPacket(p.destPort, p.sourcePort, clientSeqNumNacked, p.seqNum, true, false, false, 10, null);
                    
                    TCPWrapper.send(ackPacket, this.address);
                  
                    transitionState(this.state, State.ESTABLISHED);
                    try{
                        //System.out.println"NOTIFY ALL, SYN_SENT is " + p.seqNum);
                        this.notifyAll();
                    }
                    catch(Exception e){
                        //System.out.println"SERVER: LISTEN: notify catch");
                    }
                    break;
                }
                else{
                    //System.out.println"SYN_SENT: no synack flag");
                }
                break;   
            case SYN_RCVD:
                // SERVER CODE
                if (p.ackFlag){
                    //System.out.println"SYN_RCVD: ackFlag");
                    cancelTimer(p.ackNum);
                    transitionState(this.state, State.ESTABLISHED);
                    
                    try{
                        //System.out.println"NOTIFY ALL, SYN_RCVD is " + p.seqNum);
                        this.notifyAll();
                    }
                        
                    catch(Exception e){
                        //System.out.println"SERVER: LISTEN: notify catch");
                    }
                    break;
                }
                else if (p.synFlag){ // SERVER CODE
                    cancelOnlyTimer();
                    isRetransmit = true;
                    transitionState(this.state, State.LISTEN);
                    receivePacket(p);
                    break;
                    
                }
                //Remember numNacked does not increment for duplicate packets
                else{
                    //System.out.println"SYN_RCVD: no ack flag");
                }
                break;
            case ESTABLISHED:
                if (p.finFlag){ // SERVER CODE
                    //System.out.println"ESTABLISHED: fin flag");
                    try{
                        if (establishedFlag == true){
                            System.out.println("Retransmitting ACK to FIN packet");
                        }
                        else if (establishedFlag == false){
                            establishedFlag = true;
                        }
                        sendAckServer();
                    }
                    catch(Exception e){ }
                    
    
                    break;
                    
                }
                else if (p.synFlag && p.ackFlag){ // CLIENT CODE
                    //System.out.println"ESTABLISHED: Synack");
                    transitionState(this.state, State.SYN_SENT);
                    //System.out.println"ESTABLISHED: isRetransmit is " + isRetransmit);
                    //System.out.println"ESTABLISHED: clientSeqNumNacked before is " + clientSeqNumNacked);
                    
                    isRetransmit = true;
                    if (!isRetransmit){
                        clientSeqNumNacked--;
                    }
                    
                    
                    //System.out.println"ESTABLISHED: clientSeqNumNacked after is " + clientSeqNumNacked);
                    receivePacket(p);
                    break;
                }
                else{
                    //System.out.println"ESTABLISHED: No Fin or Synack");
                }
                break;
            case FIN_WAIT_1:
                if (p.finFlag){ // CLIENT CODE
                    //System.out.println"FIN_WAIT_1: fin flag");
                    ackPacket = new TCPPacket(this.localport, this.port, clientSeqNumNacked, p.seqNum, true, false, false, 10, null);
                    
                    increaseNacked();
                    
                    
                    if (closingFlag){
                        System.out.println("Retransmitting FIN to ACK packet");
                    
                    }
                    else{
                        closingFlag = true;
                    }
                    
                    TCPWrapper.send(ackPacket, this.address);
                    
                    transitionState(this.state, State.CLOSING);
                    try{
                        //System.out.println"NOTIFY ALL, FIN_WAIT_1, Fin. seqNum is " + p.seqNum);
                        this.notifyAll();
                    }
                    catch(Exception e){
                        //System.out.println"SERVER: FIN_WAIT_1: notify catch");
                    }
                }
                else if (p.synFlag && p.ackFlag){ // CLIENT CODE
                    //System.out.println"FIN_WAIT_1: Synack");
                    transitionState(this.state, State.SYN_SENT);
                    cancelOnlyTimer();
                    //cancelTimer(p.seqNum);
                    clientSeqNumNacked--;
                    //System.out.println"FIN_WAIT_1: decreased clientSeqNumNacked to " + clientSeqNumNacked);
                    receivePacket(p);
                    break;
                }
                else if (p.ackFlag){ // CLIENT CODE
                    //System.out.println"FIN_WAIT_1: ack flag");
                    cancelTimer(p.ackNum);
                    transitionState(this.state, State.FIN_WAIT_2);
                    try{
                        //System.out.println"NOTIFY ALL, FIN_WAIT_1 is " + p.seqNum);
                        this.notifyAll();
                    }
                    catch(Exception e){
                        //System.out.println"CLIENT: FIN_WAIT_1: notify catch");
                    }
                }
                else{
                    //System.out.println"FIN_WAIT_1: no fin, synack, or ack");
                }
                break;
            case FIN_WAIT_2:
                if (p.finFlag){ //CLIENT CODE
                    //System.out.println"FIN_WAIT_2: fin flag");
                    ackPacket = new TCPPacket(localport, port, clientSeqNumNacked, p.seqNum, true, false, false, 10, null);
                    
                    increaseNacked();
                    TCPWrapper.send(ackPacket, this.address);
                    transitionState(this.state, State.TIME_WAIT);
                    
                    try{
                        //System.out.println"NOTIFY ALL, FIN_WAIT_2 is " + p.seqNum);
                        this.notifyAll();
                    }
                    catch(Exception e){
                        //System.out.println"FIN_WAIT_2: catch exception");
                    }
                    break;
                }
                else{
                     //System.out.println"FIN_WAIT_2: no fin flag");
                }
                break;
            case CLOSE_WAIT:
                if (p.finFlag){
                    sendTimedPacket(p);
                    try{
                        this.notifyAll();
                    }
                    catch (Exception e){ }
                }
                //System.out.println"CLOSE WAIT: GOT PACKET");// SERVER CODE
                break;
            case CLOSING:
                if (p.ackFlag){ // CLIENT CODE
                    //System.out.println"CLOSING: ack flag");
                    cancelTimer(p.ackNum);
                    transitionState(this.state, State.TIME_WAIT);
                    //synchronized(this.isClient){
                        try{
                            //System.out.println"NOTIFY ALL, CLOSING seqNum is " + p.seqNum);
                            this.notifyAll();
                        }
                        catch(Exception e){
                            //System.out.println"CLOSING: catch exception");
                        }
                    //}
                }
                if (p.finFlag){ //CLIENT CODE
                    //System.out.println"CLOSING: fin flag");
                    transitionState(this.state, State.FIN_WAIT_1);
                    receivePacket(p);
                    break;
                }
                else{
                     //System.out.println"CLOSING: no ack flag");
                }
                break;
            case LAST_ACK: // SERVER CODE
                //System.out.println"SERVER: LAST_ACK");
                if (p.ackFlag){
                    //System.out.println"SERVER: LAST_ACK: ack\n");
                    cancelTimer(p.ackNum);
                    transitionState(this.state, State.TIME_WAIT);
                    
                    try{
                        //System.out.println"NOTIFY ALL, LAST_ACK is " + p.seqNum);
                        this.notifyAll();
                    }
                    
                    catch(Exception e){
                        //System.out.println"LAST_ACK: catch exception");
                    }
            
                }
                else{
                    //System.out.println"LAST_ACK: no ack flag");
                }
                break;
            case TIME_WAIT:
                if (p.finFlag){
                    //System.out.println"TIME_WAIT: fin flag");
                    //System.out.println"clientSeqNumNacked is " + clientSeqNumNacked);
                    if (clientSeqNumNacked == -1){ // SERVER CODE
                        transitionState(this.state, State.ESTABLISHED);
                        cancelOnlyTimer();
                   
                        try{
                            this.notifyAll();
                            return;
                        }
                        catch(Exception e){
                            //System.out.println"TIME_WAIT close catch");
                        }
                        receivePacket(p);
                        break;
                    }
                    else{                       // CLIENT CODE
                        //System.out.println"TIME_WAIT: fin flag and client");
                        //transitionState(this.state, State.FIN_WAIT_2);
                        //cancelTimer(p.seqNum);
                        cancelOnlyTimer();
                        
                        try{
                            sendAckClient();
                        }
                        catch(Exception e){
                        
                        }
                        TCPTimerTask timer;
                        //System.out.println"about to set 30 second timer");
                        timer = createTimerTask(30000, null);
                        break;
                    }
                }
                else{
                    //System.out.println"TIME_WAIT: else case");
                }
                break;
        
            default:
                //System.out.println"StudentSocketImpl: receivePacket: default case for state");
        }
        /*
        for (int i = 0; i < packetBuffer.length; i++){
            //System.out.println"StudentSocketImpl: receivePacket: packet[" + i + "] is " + packetBuffer[i]);
        }
        */
    }

    /** 
    * Waits for an incoming connection to arrive to connect this socket to
    * Ultimately this is called by the application calling 
    * ServerSocket.accept(), but this method belongs to the Socket object 
    * that will be returned, not the listening ServerSocket.
    * Note that localport is already set prior to this being called.
    */
    public synchronized void acceptConnection() throws IOException {
        //System.out.println"StudentSocketImpl: acceptConnection");
        //SERVER CODE
        //System.out.println"SERVER: localport is " + localport);
        this.isServer = new Boolean (true);
        
        D.registerListeningSocket(this.localport, this);
        transitionState(this.state, State.LISTEN);
        serverSeqNumNacked = makeRandom();
        
        serverSeqNumAcked = serverSeqNumAcked;
        
        while(this.state != State.ESTABLISHED && this.state != State.SYN_RCVD){
            try{
                this.wait();
            }
            catch(Exception e){
                //System.out.println"SERVER: LISTEN: notify catch");
            }
        }
    }


    /**
    * Returns an input stream for this socket.  Note that this method cannot
    * create a NEW InputStream, but must return a reference to an 
    * existing InputStream (that you create elsewhere) because it may be
    * called more than once.
    *
    * @return     a stream for reading from this socket.
    * @exception  IOException  if an I/O error occurs when creating the
    *               input stream.
    */
    public InputStream getInputStream() throws IOException {
        //System.out.println"StudentSocketImpl: getInputStream");
        return null;
        
    }

    /**
    * Returns an output stream for this socket.  Note that this method cannot
    * create a NEW InputStream, but must return a reference to an 
    * existing InputStream (that you create elsewhere) because it may be
    * called more than once.
    *
    * @return     an output stream for writing to this socket.
    * @exception  IOException  if an I/O error occurs when creating the
    *               output stream.
    */
    public OutputStream getOutputStream() throws IOException {
        //System.out.println"StudentSocketImpl: getOutputStream");
        // project 4 return appOS;
        return null;
    }
    
    
    public synchronized void sendFinClient() throws IOException{
        //System.out.println"closeClient: sendFin: clientSeqNumNacked before is " + clientSeqNumNacked);
        
        increaseNacked();
        //System.out.println"closeClient: sendFin: clientSeqNumNacked before is " +clientSeqNumNacked);
        
        TCPPacket finPacket = new TCPPacket(localport, port, clientSeqNumNacked, clientSeqNumAcked, false, false, true, 10, null);
        //T-Mute: //System.out.println"closeClient: sendFin: created the finPacket");
        
        sendTimedPacket(finPacket);
        
        transitionState(this.state, State.FIN_WAIT_1);
        
        //T-Mute: //System.out.println"closeServer: sent the finPacket");
    }
    
    public synchronized void sendFinServer() throws IOException{
        //System.out.println"closeServer: sendFin: clientSeqNumNacked before is " + serverSeqNumNacked);
        
        increaseNacked();
        //System.out.println"closeServer: sendFin: clientSeqNumNacked before is " + serverSeqNumNacked);
        
        TCPPacket finPacket = new TCPPacket(localport, port, serverSeqNumNacked, serverSeqNumAcked, false, false, true, 10, null);
        //T-Mute //System.out.println"closeServer: sendFin: created the finPacket");
        
        sendTimedPacket(finPacket);
        
        transitionState(this.state, State.LAST_ACK);
        
        //T-Mute//System.out.println"closeServer: sent the finPacket");
    }
    
    public synchronized void sendAckClient() throws IOException{
        //System.out.println"sendAckClient: clientSeqNumNacked before is " + serverSeqNumNacked);
        
        if (timeWaitFlag == true){
            System.out.println("Retransmitting FIN to ACK packet");
        }
        else if (timeWaitFlag == false){
            timeWaitFlag = true;
        }
        
        increaseNacked();
        //System.out.println"sendAckClient: clientSeqNumNacked before is " + serverSeqNumNacked);
        
        TCPPacket ackPacket = new TCPPacket(localport, port, serverSeqNumNacked, serverSeqNumAcked, true, false, false, 10, null);
        //T-Mute: //System.out.println"sendAckServer: created the ackPacket");
        
        TCPWrapper.send(ackPacket, address);
        //T-Mute: sendTimedPacket(ackPacket);
        
        //transitionState(this.state, State.CLOSE_WAIT);
        
        //T-Mute: //System.out.println"sendAckServer: sent the ackPacket");
    }
    
    public synchronized void sendAckServer() throws IOException{
        //System.out.println"sendAckServer: clientSeqNumNacked before is " + serverSeqNumNacked);
        
        increaseNacked();
        //System.out.println"sendAckServer: clientSeqNumNacked before is " + serverSeqNumNacked);
        
        TCPPacket ackPacket = new TCPPacket(localport, port, serverSeqNumNacked, serverSeqNumAcked, true, false, false, 10, null);
        //T-Mute: //System.out.println"sendAckServer: created the ackPacket");
        
        TCPWrapper.send(ackPacket, address);
        //T-Mute: sendTimedPacket(ackPacket);
        
        transitionState(this.state, State.CLOSE_WAIT);
        try{
            this.notifyAll();
        }
        catch(Exception e){
            
        }
        
        //T-Mute: //System.out.println"sendAckServer: sent the ackPacket");
    }
    
    public synchronized void closeClient(int code) throws IOException{
        // 0 all
        
        sendFinClient();
        
        while (this.state != State.CLOSING && this.state != State.FIN_WAIT_2){
            if (this.state == State.ESTABLISHED){
                return;
            }
            try{
                //System.out.println"CLIENT: close: waiting for CLOSING or FW2");
                this.wait();
            }
            catch(Exception e){
                //System.out.println"CLIENT: close: CLOSING and FIN_WAIT_2 wait catch");
            }
        }
        
        while (this.state != State.TIME_WAIT){
            try{
            
                //System.out.println"CLIENT: close: state is " + this.state);
                //System.out.println"CLIENT: close: serverWait is " +serverWait);
                this.wait();
            }
            catch(Exception e){
                //System.out.println"CLIENT: close: TIME_WAIT wait catch");
            }
        }
        TCPTimerTask timer;
        //System.out.println"about to set 30 second timer");
        timer = createTimerTask(30000, null);
        while (this.state != State.CLOSED){ // unitl I'm closed, go back to
                                            // listening for packets
            try{
                //System.out.println"closeClient: Waiting for CLOSED");
                this.wait();
            }
            catch(Exception e){
                //System.out.println"CLIENT: close: CLOSE wait catch");
            }
        }
        //System.out.println"CLIENT CLOSED OFFICIALLY");
        this.localport = -1;
    }

    public synchronized void closeServer(int code) throws IOException{
        
        ////System.out.println"SERVER in CLOSED");
        ////System.out.println"listeningTable is " + D.listeningTable.toString());
        ////System.out.println"hashTableKey is " + D.getHashTableKey(localport));
        
        while (this.state != State.CLOSE_WAIT){
            try{
                //System.out.println"SERVER: close: about to wait for CLOSE_WAIT");
                //System.out.println"SERVER: close: my state is " + this.state);
                this.wait();
            }
            catch(Exception e){
                //System.out.println"SERVER: close: CLOSE_WAIT wait catch");
            }
        }
        
        closedFlag++;
        
        sendFinServer();

        while (this.state != State.TIME_WAIT){
            try{
                //System.out.println"SERVER: CLOSE: state is " + this.state);
                //System.out.println"SERVER: CLOSE: serverWait is " + serverWait);
                //System.out.println"clientSeqNumNacked is " + clientSeqNumNacked + "\n");
                if (this.state == State.CLOSE_WAIT){
                    //System.out.println"close: state is CLOSE_WAIT. Killing close with return\n\n\n\n\n\n");
                    return;
                }
                this.wait();
            }
            catch (Exception e){
                //System.out.println"SERVER: close: TIME_WAIT_S wait catch");
            }
        }
        
        TCPTimerTask timer = createTimerTask(30000, null);
        //System.out.println"SERVER 30 sec timer created");
        while (this.state != State.CLOSED){ // Can listen for more packets
            try{
                if (this.state == State.ESTABLISHED || this.state == State.CLOSE_WAIT){
                    return;
                }
                //System.out.println"close: about to wait for closed");
                this.wait();
            }
            catch (Exception e){ }
        }
        //System.out.println"SERVER CLOSED OFFICIALLY");
        this.localport = -1;
    }
    
    
    /**
    * Closes this socket. 
    *
    * @exception  IOException  if an I/O error occurs when closing this socket.
    */
    public synchronized void close() throws IOException {
        //System.out.println"INSIDE OF CLOSE");
        closedFlag++;
        if (clientSeqNumNacked != -1){ //Client Code
            while(clientClosed == false){
                closeClient(0);
            }
        }
        else{ //Server code
            if (this.state == State.CLOSED){
                return;
            }
            while (serverClosed == false){
                closeServer(0);
            }
            
        }
        
    }

    /** 
    * create TCPTimerTask instance, handling tcpTimer creation
    * @param delay time in milliseconds before call
    * @param ref generic reference to be returned to handleTimer
    */
    private TCPTimerTask createTimerTask(long delay, Object ref){
        //System.out.println"StudentSocketImpl: createTimerTask");
        if(tcpTimer == null){
            //T-Mute: System.out.println("createTimerTask: tcpTimer is null");
            tcpTimer = new Timer(false);
        }
        //T-Mute: System.out.println("createTimerTask: tcpTimer is not null");
        return new TCPTimerTask(tcpTimer, delay, this, ref);
    }


    /**
    * handle timer expiration (called by TCPTimerTask)
    * @param ref Generic reference that can be used by the timer to return 
    * information.
    */
    public synchronized void handleTimer(Object ref){
        //System.out.println"StudentSocketImpl: handleTimer:timer went off");
        if (ref == null){ // FOR CLOSING
            //System.out.println"HandleTimer: ref is null");
            tcpTimer.cancel();
            tcpTimer = null;
             
            if (this.state == State.TIME_WAIT){
                transitionState(this.state, State.CLOSED);
                try{
                    //System.out.println"NOTIFY ALL, ref is NULL");
                    this.notifyAll();
                    clientClosed = true;
                    serverClosed = true;
                }
                catch (Exception e){
                    
                }
            }
            else{
                //System.out.println"handleTimer: state is " + this.state);
            }
            return;
        }
        
        TCPPacket p = (TCPPacket) ref;
        int seqNum = p.seqNum;
        State toState = (State) stateTable.get(seqNum); // State I'm supposed be
                                                    // on based on StateTable
                                                    
                                                    
        transitionState(this.state, toState);
        try{
            //System.out.println"NOTIFY ALL, seqNum is " + seqNum);
            this.notifyAll();
        }
        catch(Exception e){ }
        
        //System.out.println"HANDLE TIMER: seqNum is " + seqNum + "\t state is " + this.state);
        
        
        if (this.clientSeqNumNacked != -1){ // SERVER CODE
            this.serverSeqNumAcked = p.seqNum;
            //System.out.println"Handle Timer: serverSeqNumAcked is " + serverSeqNumAcked);
        }
        else{                               // CLIENT CODE
            this.clientSeqNumAcked = p.seqNum;
            //System.out.println"Handle Timer: clientSeqNumAcked is " + clientSeqNumAcked);
        }
        
        
        cancelTimer(seqNum);
        
        
        this.isRetransmit = true;
        
        
        if (this.state == State.LISTEN){
            System.out.println("Retransmitting SYN ACK packet");
            receivePacket(p);
            return;
        }
        else if (this.state == State.ESTABLISHED){
            try{
                // CLIENT CODE from FIN_WAIT_1 to ESTABLISHED
                System.out.println("Retransmitting FIN packet");
                sendFinClient();
                return;
            }
            catch(Exception e){
            
            }
        }
        else if (this.state == State.CLOSE_WAIT){
            try{
                System.out.println("Retransmitting SYN packet");
                sendFinServer();
                return;
            }
            catch(Exception e){ }
        }
        else if (this.state == State.FIN_WAIT_1){
            //System.out.println"handleTimer: state is FIN_WAIT_1");
            /*
            try{
                this.notifyAll();
            }
            catch(Exception e){
                
            }
            */
        }
        else if (this.state == State.CLOSED){
            try{
                System.out.println("Retransmitting SYN packet");
                connect(this.address, this.port);
                return;
            }
            catch(Exception e){ }
        }
        else{
            //System.out.println"HANDLE TIMER UNEXPECTED STATE");
        }
        
    
    }

    public int makeRandom(){
        //Remember to rethink this
        Random rand = new Random();
        return rand.nextInt(1000);
    }
    
    // CODE TO DISTINGUISH
    public void transitionState(State from, State to){
        this.state = to;
        System.out.println("!!! " + from.toString() + "->" + to.toString());
    }
    
     private synchronized void sendTimedPacket(TCPPacket p){
        int time = 1000;
        TCPTimerTask timer = createTimerTask(time, p);
        //System.out.println"sendTimedPacket: p.seqNum is " + p.seqNum);
        //System.out.println"sendTimedPacket: this.state is " + this.state);
        //System.out.println"sendTimedPacket: timer is " + timer);
        int seqNum = p.seqNum;
        State tempState = (State) stateTable.put(seqNum, this.state);
        //System.out.println"sendTimedPacket: tempState is " + tempState);
        TCPTimerTask tempTimer = (TCPTimerTask) timerTable.put(seqNum, timer);
        //System.out.println"sendTimedPacket: tempTimer is " + tempTimer);
        //System.out.println"sendTimedPacket: this.address is " + this.address);
        TCPWrapper.send(p, this.address);
        
        //System.out.println"SENT TIMED PACKET: seqNum is " + seqNum + "\t state is " + this.state);
        
    }
    
    private synchronized void cancelTimer(int seqNum){
        //System.out.println"CANCELING TIMER FOR " + seqNum);
        //System.out.println"Before timerTable was " + timerTable.toString());
        //TCPTimerTask timer = (TCPTimerTask) timerTable.get(seqNum);
        if (tcpTimer != null){
            tcpTimer.cancel();
        }
        tcpTimer = null;
        timerTable.remove(seqNum);
        stateTable.remove(seqNum);
        //System.out.println"Now timerTable is " + timerTable.toString());
    }
    
    
    private synchronized void increaseNacked(){
        if (isClient.booleanValue()){
        //T-Mute:     //System.out.println"isClient with clientSeqNumNacked of " + clientSeqNumNacked);
            if (!isRetransmit){
                clientSeqNumNacked++;
        //T-Mute:         System.out.println("is not retransmitting so clientSeqNumNacked is " + clientSeqNumNacked);
            }
            else{
          //T-Mute:       System.out.println("is retransmitting so clientSeqNumNacked is " + clientSeqNumNacked);
            }
        }
        else{
         //T-Mute:    System.out.println("not client");
        }
        if (isServer.booleanValue()){
         //T-Mute:    System.out.println("isServer with serverSeqNumNacked of " + serverSeqNumNacked);
            if (!isRetransmit){
                serverSeqNumNacked++;
                //System.out.println"is not retransmitting with serverSeqNumNacked of " + serverSeqNumNacked);
            }
            else{
         //T-Mute:        System.out.println("is retransmitting with serverSeqNumNacked of " + serverSeqNumNacked);
            }
        }
        else{
        //T-Mute:     System.out.println("not server");
        }
        
    }
    
    private synchronized void cancelOnlyTimer(){
     //T-Mute:    System.out.println("timerTable before Nuke is " + timerTable.toString());
        timerTable.clear();
        if (tcpTimer != null){
            tcpTimer.cancel();
        }
        tcpTimer = null;
     //T-Mute:    System.out.println("timerTable after Nuke is " + timerTable.toString());
    }
}
