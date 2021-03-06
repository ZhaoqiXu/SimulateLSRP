package socs.network.node;

import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.message.LSA;
import socs.network.util.Configuration;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Vector;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;

public class Router {

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();

  //assuming that all routers are with 4 ports
  Link[] ports = new Link[4];

  // Server instance for socket programming
  private Server server;

  // Client socket array, 4 ports 4 sockets
  private Socket[] clientSocket = new Socket[4];

  public Router(Configuration config, char n) {

      rd.simulatedIPAddress = config.getString("socs.network.router.ip");

      lsd = new LinkStateDatabase(rd);
      InetAddress IP;
      try {
          IP = InetAddress.getLocalHost();
          rd.processIPAddress = IP.getHostAddress(); // TODO: change to "localhost" works with the socket
          rd.processIPAddress = "localhost";
      } catch (UnknownHostException e) {
          System.err.println("Unknown Host, Router exit");
          System.exit(-1);
      }
      rd.processPortNumber = (short)(7000 + n - 49);
      System.out.println("Host name of Router "+(n-48)+ " : "+rd.processIPAddress);
      System.out.println("Port number of Router "+(n-48)+ " : "+rd.processPortNumber);
      lsd = new LinkStateDatabase(rd);
      server = new Server(this);
      new Thread(server).start();
      HeartbeatTask heartbeat = new HeartbeatTask(this);
      Timer timer = new Timer();
      timer.schedule(heartbeat, 5000,10000);
  }

  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip address of the destination simulated router
   */
  private void processDetect(String destinationIP) {
    if (destinationIP.equals(rd.simulatedIPAddress)) System.out.println("Please detect other routers!");
    else System.out.println(lsd.getShortestPath(destinationIP));
  }

  /**
   * disconnect with the router identified by the given destination ip address
   * Notice: this command should trigger the synchronization of database
   *
   * @param portNumber the port number which the link attaches at
   */
  private void processDisconnect(short portNumber) {

      Link link = null;
      int portIndex = -1; // port index

      // find link with the desired processPortNumber
      for (int i=0; i<ports.length; i++) {
          if (ports[i] != null && ports[i].router2.processPortNumber == portNumber) {
              link = ports[i];
              portIndex = i;
              break;
          }
      }

      if (null == link && portIndex == -1) {
          System.out.println("processPortNumber " + portNumber + " does not exist.");
          return;
      }

      // update LSA of remote router
      String remoteIP = link.router2.simulatedIPAddress;
      LSA remoteLSA = lsd._store.get(remoteIP);
      remoteLSA.lsaSeqNumber++;
      Iterator<LinkDescription> iter = remoteLSA.links.iterator();
      while (iter.hasNext()) {
          LinkDescription ld = iter.next();
          if (ld.linkID.equals(rd.simulatedIPAddress)) {
              iter.remove();
          }
      }


      // update LSA of local router
      LSA localLSA = lsd._store.get(rd.simulatedIPAddress);
      localLSA.lsaSeqNumber++;
      Iterator<LinkDescription> iter2 = localLSA.links.iterator();
      while (iter2.hasNext()) {
          LinkDescription ld = iter2.next();
          if (ld.linkID.equals(remoteIP)) {
              iter2.remove();
          }
      }

      // delete link in ports[]
      System.out.println(link.router2.simulatedIPAddress + " is disconnected.");
      ports[portIndex] = null;

      // create lsa array
      Vector<LSA> lsaArray = new Vector<LSA>();
      Iterator it = lsd._store.entrySet().iterator();

      while (it.hasNext()) {
          Map.Entry pair = (Map.Entry) it.next();
          lsaArray.add((LSA) pair.getValue());
      }

      // share the LSP with all neighbors
      broadcastLSP(lsaArray);

      // close socket
      try {
          if (clientSocket[portIndex] != null) {
              clientSocket[portIndex].close();
          }
      } catch (IOException e) {
          System.err.println("IO Exception caused by diconnect: ");
          e.printStackTrace();
      }
  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to identify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */
  private void processAttach(String processIP, short processPort,
                             String simulatedIP, short weight) {

      // check if it tries to attach itself
      if (rd.simulatedIPAddress.equals(simulatedIP)) {
          System.err.println("Attaching to yourself is not allowed!");
          return;
      }

      // check if the target router has already been attached
      for (int i=0; i<4; i++) {
          if (null != ports[i] && ports[i].router2.simulatedIPAddress.equals(simulatedIP)) {
              System.err.println("This router has already been attached!");
              return;
          }
      }

      // create a RouterDescription for the remote router
      RouterDescription remote_rd = new RouterDescription(processIP, processPort, simulatedIP);
      // create a link of these two routers
      Link link = new Link(rd, remote_rd, weight);
      // put it into ports[]
      for (int i=0; i<4; i++) {
          if (null == ports[i]) {
              ports[i] = link;
              System.out.println("Link is established between " + rd.simulatedIPAddress
                      + " and " + remote_rd.simulatedIPAddress + ".");
              return;
          }
      }
      // no more free port
      System.err.println("All ports are occupied, link cannot be established.");
      return;
  }

    /**
     * Forward lsp to neighbors except the sender
     * @param received
     */
    public void forwardLSP(SOSPFPacket received) {
        for (Link l : ports) {
            if (null != l && l.router2.status == RouterStatus.TWO_WAY && !l.router2.simulatedIPAddress.equals(received.srcIP)) {
                SOSPFPacket lsp = new SOSPFPacket(l.router1.processIPAddress, l.router1.processPortNumber,
                        l.router1.simulatedIPAddress, l.router2.simulatedIPAddress, (short) 1, "", "",
                        received.lsaArray, l.weight);
                try {
                    Socket clientSocket = new Socket(l.router2.processIPAddress, l.router2.processPortNumber);
                    ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
                    outStream.writeObject(lsp);
                    outStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        System.out.println("LSPs are forwarded to neighbors.");
    }

    /**
     * broadcast lsp
     * @param lsaArray
     */
  public void broadcastLSP(Vector<LSA> lsaArray) {
      for (Link l : ports) {
          if (null != l && l.router2.status == RouterStatus.TWO_WAY) {
              SOSPFPacket lsp = new SOSPFPacket(l.router1.processIPAddress, l.router1.processPortNumber,
                      l.router1.simulatedIPAddress, l.router2.simulatedIPAddress, (short) 1, "", "",
                      lsaArray, l.weight);
              try {
                  Socket clientSocket = new Socket(l.router2.processIPAddress, l.router2.processPortNumber);
                  ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
                  outStream.writeObject(lsp);
                  outStream.close();
              } catch (Exception e) {
                  e.printStackTrace();
              }
          }
      }
      System.out.println("LSPs are broadcast to neighbors.");
  }

    /**
     * conduct the LSAUPDATE and broadcast LSPs
     * @param remoteLinkID
     * @param remotePortNum
     * @param weight
     */
  public void lsaUpdate(String remoteLinkID, short remotePortNum, short weight) {
      // create link description for this new link
      LinkDescription ld = new LinkDescription();
      ld.linkID = remoteLinkID;
      ld.portNum = remotePortNum;
      ld.tosMetrics = weight;

      // add this new link to the LSA
      LSA lsa = lsd._store.get(rd.simulatedIPAddress);
      lsa.links.add(ld);
      lsa.lsaSeqNumber++;

      // create lsa array
      Vector<LSA> lsaArray = new Vector<LSA>();
      //lsaArray.add(lsa);
      Iterator it = lsd._store.entrySet().iterator();

      while (it.hasNext()) {
          Map.Entry pair = (Map.Entry)it.next();
          lsaArray.add((LSA) pair.getValue());
      }

      // share the LSP with all neighbors
      broadcastLSP(lsaArray);
  }


  /**
   * broadcast Hello to neighbors
   */
  private void processStart() {

      for (int i=0; i<4; i++) {
          if (null == ports[i]) continue;
          // if already neighbors, no need to send hello again
          if (ports[i].router2.status == RouterStatus.TWO_WAY) continue;
          // create a hello packet
          SOSPFPacket packet = new SOSPFPacket(ports[i].router1.processIPAddress, ports[i].router1.processPortNumber,
                  ports[i].router1.simulatedIPAddress, ports[i].router2.simulatedIPAddress, (short) 0,
                  "", "", null, ports[i].weight);
          try {
              // create a client socket
              clientSocket[i] = new Socket(ports[i].router2.processIPAddress, ports[i].router2.processPortNumber);
              ObjectOutputStream out = new ObjectOutputStream(clientSocket[i].getOutputStream());
              // send first hello packet
              out.writeObject(packet);
              ObjectInputStream in = new ObjectInputStream(clientSocket[i].getInputStream());
              // blocking operation
              SOSPFPacket received = (SOSPFPacket) in.readObject();
              if (received.sospfType == 0){
                  System.out.println("received HELLO from " + received.srcIP + ";");
                  ports[i].router2.status = RouterStatus.TWO_WAY;
                  System.out.println("set " + received.srcIP + " state to TWO_WAY;");
              }
              else {
                  System.err.println("Error in received packet!");
              }
              // send second hello packet
              out.writeObject(packet);

              // lsaUpdate
              lsaUpdate(ports[i].router2.simulatedIPAddress, ports[i].router2.processPortNumber, ports[i].weight);

              in.close();
              out.close();
          } catch (Exception e){
              if (e instanceof IOException)
                  System.err.println("Port cannot be used");
              else if (e instanceof ClassNotFoundException)
                  System.err.println("In stream object class not found");
              else
                  System.err.println(e.getMessage());
          }
      }
  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to identify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * This command does trigger the link database synchronization
   */
  private void processConnect(String processIP, short processPort,
                              String simulatedIP, short weight) {
      processAttach(processIP,processPort,simulatedIP,weight);
      processStart();
  }

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
      int i = 1;
      for (Link l : ports) {
          if (null != l && l.router2.status == RouterStatus.TWO_WAY) {
              System.out.println("IP Address of the neighbor" + i++);
              System.out.println(l.router2.simulatedIPAddress);
          }
      }
      if (i == 1)
          System.out.println("No neighbors on this router!");
  }

  /**
   * disconnect with all neighbors and quit the program
   */
  private void processQuit() {
      System.exit(0);
  }

  public void terminal() {

    try {
      InputStreamReader isReader = new InputStreamReader(System.in);
      BufferedReader br = new BufferedReader(isReader);
      System.out.print(">> ");
      String command = br.readLine();
      while (true) {
        if (command.startsWith("detect ")) {
          String[] cmdLine = command.split(" ");
          processDetect(cmdLine[1]);
        } else if (command.startsWith("disconnect ")) {
          String[] cmdLine = command.split(" ");
          processDisconnect(Short.parseShort(cmdLine[1]));
        } else if (command.startsWith("quit")) {
          processQuit();
          break;
        } else if (command.startsWith("attach ")) {
          String[] cmdLine = command.split(" ");
          processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("start")) {
          processStart();
        } else if (command.startsWith("connect ")) {
          String[] cmdLine = command.split(" ");
          processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("neighbors")) {
            //output neighbors
            processNeighbors();
        } else if (command.equals("showlsd")) {
            // show lsd
            System.out.println(lsd.toString());
        } else {
          //invalid command
          System.err.println("Invalid command, please enter again!");
        }
        System.out.print(">> ");
        command = br.readLine();
      }
      isReader.close();
      br.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
