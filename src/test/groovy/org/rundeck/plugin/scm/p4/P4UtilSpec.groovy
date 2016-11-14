package org.rundeck.plugin.scm.p4

import com.perforce.p4java.core.IChangelist
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.impl.generic.core.User
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
        IServer p4Server = BaseP4PluginSpec.createPerforceServer(tmpServerRoot)
        BaseP4PluginSpec.createPerforceClient(p4Server, originDir)
        new File(originDir, "test1") << 'data'

        User user = User.newUser("test.user", "test@example.com", "Test User", "")
        p4Server.createUser(user, false)
        IChangelist change = p4Server.getChangelist(IChangelist.DEFAULT)
        change.setDescription("test commit")
        change.setUsername("test.user")
        p4Server.getCurrentClient().addFiles(FileSpecBuilder.makeFileSpecList("test1"), new AddFilesOptions())
        change.submit(new SubmitOptions())
        change.refresh()

        when:
        def result1 = p4Server.getChangelist(change.getId())

        then:
        result1 == change
    }
}
