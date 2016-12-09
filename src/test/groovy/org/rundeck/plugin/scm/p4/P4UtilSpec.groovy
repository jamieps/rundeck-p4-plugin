package org.rundeck.plugin.scm.p4

import com.perforce.p4java.client.IClient
import com.perforce.p4java.core.ChangelistStatus
import com.perforce.p4java.core.IChangelist
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.FileSpecOpStatus
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.impl.generic.core.Changelist
import com.perforce.p4java.impl.generic.core.User
import com.perforce.p4java.impl.mapbased.server.Server
import com.perforce.p4java.option.changelist.SubmitOptions
import com.perforce.p4java.option.client.AddFilesOptions
import com.perforce.p4java.server.IServer
import spock.lang.Specification

/**
 * Created by greg on 11/2/15.
 */
class P4UtilSpec extends Specification {
    File tempdir

    def setup() {
        tempdir = File.createTempDir("P4UtilSpec", "-test")
    }

    def cleanup() {
        if (tempdir.exists()) {
            tempdir.deleteDir()
        }
    }
    def "getcommit found"() {
        given:
        def originDir = new File(tempdir, 'origin')
        originDir.mkdirs()
        // create a p4 dir
        File tmpServerRoot = File.createTempDir("BaseP4PluginSpec", "-test")
        tmpServerRoot.deleteOnExit()
        IServer p4Server = P4Utils.getPerforceServer(P4Utils.createPerforceServer(tmpServerRoot))
        IClient p4Client = P4Utils.createPerforceClient(p4Server, originDir)
        new File(originDir, "test1") << 'data'

        User user = User.newUser("p4java", "test@example.com", "Test User", "")
        p4Server.createUser(user, false)

        Changelist changeListImpl = new Changelist(
                IChangelist.UNKNOWN,
                p4Client.getName(),
                "p4java",
                ChangelistStatus.NEW,
                new Date(),
                "test commit",
                false,
                (Server) p4Server
        )
        IChangelist change = p4Client.createChangelist(changeListImpl)

        List<IFileSpec> addedFiles = p4Client.addFiles(FileSpecBuilder
                .makeFileSpecList("//${p4Client.getName()}/test1"),
                new AddFilesOptions(changelistId: change.getId()))

        addedFiles.each { fileSpec ->
            if (fileSpec?.getOpStatus() == FileSpecOpStatus.VALID) {
                println("added: ${fileSpec.getDepotPath()}")
            } else {
                System.err.println(fileSpec.getStatusMessage())
            }
        }

        change.update()
        List<IFileSpec> submittedFiles = change.submit(new SubmitOptions())

        submittedFiles.each { fileSpec ->
            if (fileSpec?.getOpStatus() == FileSpecOpStatus.VALID) {
                println("submitted: ${fileSpec.getDepotPath()}")
            } else {
                System.err.println(fileSpec.getStatusMessage())
            }
        }

        when:
        def result1 = p4Server.getChangelist(change.getId())

        then:
        result1.getId() == change.getId()
        result1.getFiles(true).size() == change.getFiles(true).size()
        result1.getFiles(true)[0].depotPathString == change.getFiles(true)[0].depotPathString
        result1.getDescription() == "test commit\n"
    }
}
