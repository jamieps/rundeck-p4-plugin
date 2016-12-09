package org.rundeck.plugin.scm.p4

import com.perforce.p4java.client.IClient
import com.perforce.p4java.impl.generic.client.ClientView
import com.perforce.p4java.impl.mapbased.client.Client
import com.perforce.p4java.server.IServer
import com.perforce.p4java.server.ServerFactory

/**
 * Created by jamie.penman on 06/12/2016.
 */
class P4Utils {

    /**
     * Create a temporary Perforce server and client.
     *
     * @param serverRoot
     * @param clientRoot
     * @return
     */
    static SimpleTestServer createPerforceServer(final File serverRoot) {
        serverRoot.mkdirs()
        SimpleTestServer p4d = new SimpleTestServer(serverRoot.getAbsolutePath(), "r16.2")
        p4d
    }

    static IServer getPerforceServer(final SimpleTestServer p4d) {
        IServer server  = ServerFactory.getServer(p4d.getRshPort(), null)
        server.setUserName("p4java")
        server.connect()
        server
    }

    /**
     * Create a temporary Perforce client, with a specific root directory.
     *
     * @param   server      the Perforce server instance
     * @param   clientRoot  the root directory of the new client
     * @return  the client which has been created
     */
    static IClient createPerforceClient(final IServer server, final File clientRoot) {
        String clientName  = "tempClient" + UUID.randomUUID().toString().replace("-", "")
        IClient tempClient = new Client(name: clientName,
                description: "P4Java temporary client for unit testing",
                root: clientRoot.getAbsolutePath(), server: server)

        ClientView clientView = new ClientView()
        ClientView.ClientViewMapping viewMap1 = new ClientView.ClientViewMapping()
        viewMap1.setLeft("//depot/...")
        viewMap1.setRight("//${clientName}/...")
        clientView.addEntry(viewMap1)
        tempClient.setClientView(clientView)

        server.createClient(tempClient)
        tempClient = server.getClient(clientName)
        server.setCurrentClient(tempClient)

        tempClient
    }
}
