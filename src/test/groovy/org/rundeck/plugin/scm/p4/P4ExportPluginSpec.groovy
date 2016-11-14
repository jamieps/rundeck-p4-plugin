package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.jobs.JobReference
import com.dtolabs.rundeck.core.jobs.JobRevReference
import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.client.IClient
import com.perforce.p4java.core.IChangelist
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.impl.generic.core.User
import com.perforce.p4java.impl.mapbased.client.Client
import com.perforce.p4java.option.changelist.SubmitOptions
import com.perforce.p4java.option.client.AddFilesOptions
import com.perforce.p4java.option.client.EditFilesOptions
import com.perforce.p4java.server.IServer
import com.perforce.p4java.server.ServerFactory
import org.rundeck.plugin.scm.p4.config.Config
import org.rundeck.plugin.scm.p4.config.Export
import org.rundeck.plugin.scm.p4.exp.actions.CommitJobsAction
import org.rundeck.plugin.scm.p4.exp.actions.LabelAction
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Created by greg on 8/31/15.
 */
class P4ExportPluginSpec extends Specification {
    private static final String DEFAULT_P4D_PATH = "/usr/local/sbin/p4d"

    File tempDir

    def setup() {
        tempDir = File.createTempDir("P4ExportPluginSpec", "-test")
    }

    def cleanup() {
        if (tempDir.exists()) {
            tempDir.deleteDir()
        }
    }

    @Unroll
    def "create plugin, required input"() {
        given:

        def gitdir = new File(tempDir, 'scm')
        Map<String, String> input = [
                dir                  : gitdir.absolutePath,
                pathTemplate         : '${job.group}${job.name}-${job.id}.xml',
                branch               : 'master',
                committerName        : 'test user',
                committerEmail       : 'test@example.com',
                strictHostKeyChecking: 'yes',
                format               : 'xml',
                url                  : new File(tempDir, 'origin'),
        ]
        input.remove(requiredInputName)

        when:
        def config = Config.create(Export, input)

        then:
        ScmPluginInvalidInput e = thrown()
        e.message == requiredInputName + ' cannot be null'
        e.report.errors[requiredInputName] == 'cannot be null'



        where:
        _ | requiredInputName
        _ | 'dir'
        _ | 'pathTemplate'
        _ | 'branch'
        _ | 'committerName'
        _ | 'committerEmail'
        _ | 'url'
        _ | 'strictHostKeyChecking'
        _ | 'format'

    }

    def "create plugin, ok"() {
        given:

        def gitdir = new File(tempDir, 'scm')
        def origindir = new File(tempDir, 'origin')
        Export config = createTestConfig(gitdir, origindir)
        //create a p4 dir
        def git = createPerforceServer(originDir)
        git.close()

        def context = Mock(ScmOperationContext)
        when:
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(context)

        then:
        gitdir.isDirectory()
        new File(gitdir, '.p4').isDirectory()
        openGit(gitdir).repository.getFullBranch()=='refs/heads/master'

    }

    def "create plugin, using config.format in the path template"() {
        given:

        def scmDir = new File(tempDir, 'scm')
        Export config = createTestConfig(
                gitdir,
                origindir,
                [
                        pathTemplate: 'blah.${config.format}',
                        format      : format
                ]
        )
        //create a p4 dir
        createPerforceServer(origindir)

        //create plugin
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(Mock(ScmOperationContext))

        when:
        def path = plugin.mapper.fileForJob(Mock(JobReference))

        then:
        path == new File(gitdir, 'blah.' + format)

        where:
        format | _
        'xml'  | _
        'yaml' | _
    }


    def "re initialize plugin with new url"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        def origin2dir = new File(tempdir, 'origin2')
        Export config = createTestConfig(gitdir, origindir)
        //create a p4 dir
        def server = createPerforceServer(origindir)
        def commit = addCommitFile(origindir, server, 'testcommit.txt', 'blah')
        server.disconnect()

        def context = Mock(ScmOperationContext)

        //first init with origin1
        new P4ExportPlugin(config).initialize(Mock(ScmOperationContext))

        //add loose file in working dir
        def testfile=new File(gitdir,'test-file')
        testfile<<'test'


        //create origin2
        Export config2 = createTestConfig(gitdir, origin2dir)
        def server2 = createPerforceServer(origin2dir)
        def commit2 = addCommitFile(origin2dir, server2, 'testcommit.txt', 'blee')
        server2.disconnect()

        def plugin = new P4ExportPlugin(config2)

        when:
        plugin.initialize(context)

        then:
        gitdir.isDirectory()
        new File(gitdir, '.p4').isDirectory()
        //loose file has been removed
        !testfile.exists()
        new File(gitdir,'testcommit.txt').exists()
        new File(gitdir,'testcommit.txt').text=='blee'
    }

    def "re initialize plugin with new branch"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)
        //create a p4 dir
        def git = createPerforceServer(origindir)
        def commit = addCommitFile(origindir, git, 'testcommit.txt', 'blah')
        git.close()

        def context = Mock(ScmOperationContext)

        //first init with origin1
        new P4ExportPlugin(config).initialize(Mock(ScmOperationContext))

        //add loose file in working dir
        def testfile=new File(gitdir,'test-file')
        testfile<<'test'


        //create dev branch
        def git2 = openGit(origindir)
        git2.branchCreate().setName('dev').call()
        git2.checkout().setName('dev').call()
        def commit2 = addCommitFile(origindir, git2, 'testcommit.txt', 'blee')
        git2.close()

        Export config2 = createTestConfig(gitdir, origindir,[
                branch:'dev'
        ])
        def plugin = new P4ExportPlugin(config2)

        when:
        plugin.initialize(context)

        then:
        gitdir.isDirectory()
        new File(gitdir, '.p4').isDirectory()
        //loose file has been removed
        !testfile.exists()
        new File(gitdir,'testcommit.txt').exists()
        new File(gitdir,'testcommit.txt').text=='blee'
        openGit(gitdir).repository.getFullBranch()=='refs/heads/dev'
    }
    def "re initialize plugin with same branch"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)
        //create a p4 dir
        def git = createPerforceServer(origindir)
        def commit = addCommitFile(origindir, git, 'testcommit.txt', 'blah')
        git.close()

        def context = Mock(ScmOperationContext)

        //first init with origin1
        new P4ExportPlugin(config).initialize(Mock(ScmOperationContext))

        //add loose file in working dir
        def testfile=new File(gitdir,'test-file')
        testfile<<'test'


        //create dev branch
        def git2 = openGit(origindir)
        git2.branchCreate().setName('dev').call()
        git2.checkout().setName('dev').call()
        def commit2 = addCommitFile(origindir, git2, 'testcommit.txt', 'blee')
        git2.close()

        def plugin = new P4ExportPlugin(config)

        when:
        plugin.initialize(context)

        then:
        gitdir.isDirectory()
        new File(gitdir, '.p4').isDirectory()
        //loose file has been removed
        testfile.exists()
        new File(gitdir,'testcommit.txt').exists()
        new File(gitdir,'testcommit.txt').text=='blah'
        openGit(gitdir).repository.getFullBranch()=='refs/heads/master'
    }

    def "get input view for commit action"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()
        def context = Mock(ScmOperationContext)
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(context)
        def path = 'testfile'
        def localfile = new File(gitdir, path)
        localfile << 'blah'

        when:
        def view = plugin.getInputViewForAction(context, P4ExportPlugin.JOB_COMMIT_ACTION_ID)

        then:
        view.title == "Commit Changes to Git"
        view.actionId == P4ExportPlugin.JOB_COMMIT_ACTION_ID
        view.properties.size() == 3
        view.properties*.name == [
                CommitJobsAction.P_MESSAGE,
                LabelAction.P_LABEL_NAME,
                CommitJobsAction.P_PUSH
        ]

    }

    static Export createTestConfig(File tempDir, Map<String, String> override = [:]) {
        def scmDir = new File(tempDir, 'scm')
        def p4ServerRoot = new File(tempDir, 'p4root')
        Map<String, String> input = [
                dir                  : scmDir.absolutePath,
                pathTemplate         : '${job.group}${job.name}-${job.id}.xml',
                branch               : 'master',
                committerName        : 'test user',
                committerEmail       : 'test@example.com',
                strictHostKeyChecking: 'yes',
                format               : 'xml',
                uri                  : getPerforceRshURI(p4ServerRoot),
                p4ClientName         : 'foobar',
                p4Spec               : '//depot/foo/...',
        ] + override
        def config = Config.create(Export, input)
        config
    }

    def "get job status, does not exist in repo"() {
        given:

        def gitdir = new File(tempDir, 'scm')
        def origindir = new File(tempDir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)

        git.close()
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(Mock(ScmOperationContext))

        def serializer = Mock(JobSerializer)
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'name'
            getGroupPath() >> 'a/b'
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        when:
        def status = plugin.getJobStatus(jobref)

        then:
        status != null
        status.synchState == SynchState.CREATE_NEEDED
        status.commit == null
        1 * serializer.serialize('xml', _)>>{args->
            args[1].write('data'.bytes)
        }
        0 * serializer.serialize(*_)
    }

    def "get job status, exists in repo"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        def commit = addCommitFile(origindir, git, 'a/b/name-xyz.xml', 'blah')

        git.close()
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(Mock(ScmOperationContext))

        def serializer = Mock(JobSerializer)
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'name'
            getGroupPath() >> 'a/b'
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        when:
        def status = plugin.getJobStatus(jobref)

        then:
        status != null
        status.synchState == state
        status.commit != null
        status.commit.asMap().authorTimeZone != null
        status.commit.asMap().date != null
        status.commit.asMap().authorTime != null

        status.commit.asMap().message == 'test commit'
        status.commit.asMap().authorEmail == 'test@example.com'
        status.commit.asMap().authorName == 'test user1'
        status.commit.asMap().commitId == commit.name
        status.commit.asMap().commitId6 == commit.abbreviate(6).name()
        1 * serializer.serialize('xml', _) >> {
            it[1].write(contents.bytes)
        }
        0 * serializer.serialize(*_)

        where:
        contents | state
        'bloo'   | SynchState.EXPORT_NEEDED
        'blah'   | SynchState.CLEAN
    }

    def "get file diff, new content"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        if (orig) {
            def commit = addCommitFile(origindir, git, 'a/b/name-xyz.xml', orig)
        }

        git.close()
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(Mock(ScmOperationContext))

        def serializer = Mock(JobSerializer)
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'name'
            getGroupPath() >> 'a/b'
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        when:
        def diff = plugin.getFileDiff(jobref)

        then:
        diff != null
        diff.modified == modified
        diff.newNotFound == newNotFound
        diff.oldNotFound == oldNotFound
        diff.content == (modified ? """@@ -1 +1 @@
-${orig}+${contents}""" : orig ? '' : null)

        1 * serializer.serialize('xml', _) >> {
            it[1].write(contents.bytes)
        }
        0 * serializer.serialize(*_)

        where:
        orig     | contents | modified | newNotFound | oldNotFound
        'blah\n' | 'bloo\n' | true     | false       | false
        'blah\n' | 'blah\n' | false    | false       | false
        null     | 'blah\n' | false    | false       | true

    }

    def "get status clean"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(Mock(ScmOperationContext))

        when:
        def status = plugin.getStatus(Mock(ScmOperationContext))

        then:
        status!=null
        status.state==SynchState.CLEAN
        status.message==null

    }
    def "get status fetch fails"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(Mock(ScmOperationContext))

        //delete origin contents, will cause fetch to fail
        FileUtils.delete(origindir, FileUtils.RECURSIVE)

        when:
        def status = plugin.getStatus(Mock(ScmOperationContext))

        then:
        status!=null
        status.state==SynchState.REFRESH_NEEDED
        status.message=='Fetch from the repository failed: Invalid remote: origin'
    }

    static List<IFileSpec> addCommitFile(final File p4dir, final IClient p4Client, final String path, final String content) {
        User user = User.newUser("p4java", "test@example.com", "Test User", "")
        p4Client.getServer().createUser(user, false)
        IChangelist change = p4Client.getServer().getChangelist(IChangelist.DEFAULT)
        change.setDescription("test commit")
        change.setUsername("p4java")

        println "Writing to ${p4dir.getAbsolutePath()}/${path}"
        println "Client is ${p4Client.getName()}, root directory is ${p4Client.getRoot()}"
        def outfile = new File(p4dir, path)
        outfile.parentFile.mkdirs()

        if (outfile.exists() && !outfile.canWrite()) {
            p4Client.editFiles(FileSpecBuilder.makeFileSpecList("${p4dir.getAbsolutePath()}/${path}"),
                    new EditFilesOptions())
        } else {
            p4Client.addFiles(FileSpecBuilder.makeFileSpecList("${p4dir.getAbsolutePath()}/${path}"),
                    new AddFilesOptions())
        }

        outfile.withOutputStream {
            it.write(content.bytes)
        }

        change.submit(new SubmitOptions())
    }

    def "get local file and path for job"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir, [pathTemplate: template])

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(Mock(ScmOperationContext))


        def jobref = Stub(JobRevReference) {
            getJobName() >> 'name'
            getGroupPath() >> groupPath
            getId() >> 'xyz'
        }
        when:
        def result = plugin.getLocalFileForJob(jobref)
        def relative = plugin.getRelativePathForJob(jobref)

        then:
        result == new File(gitdir, path)
        relative == path


        where:
        groupPath | path               | template
        'a/b'     | 'a/b/name-xyz.xml' | '${job.group}${job.name}-${job.id}.xml'
        'a'       | 'a/name-xyz.xml'   | '${job.group}${job.name}-${job.id}.xml'
        ''        | 'name-xyz.xml'     | '${job.group}${job.name}-${job.id}.xml'
        null      | 'name-xyz.xml'     | '${job.group}${job.name}-${job.id}.xml'

        'a/b'     | 'job-xyz.xml'      | 'job-${job.id}.xml'
        'a'       | 'job-xyz.xml'      | 'job-${job.id}.xml'
        ''        | 'job-xyz.xml'      | 'job-${job.id}.xml'
        null      | 'job-xyz.xml'      | 'job-${job.id}.xml'
    }

    static String getPerforceRshURI(final File serverRoot) {
        String p4dPath      = DEFAULT_P4D_PATH
        String envP4dPath   = System.getenv("RUNDECK_P4D_PATH")
        if (envP4dPath != null && !envP4dPath.isEmpty()) {
            p4dPath = envP4dPath
        }
        return "p4jrsh://${p4dPath} -r ${serverRoot.getAbsolutePath()} -L p4d.log -i --java"
    }

    static IServer createPerforceServer(final File serverRoot, final File clientRoot) {
        serverRoot.mkdirs()
        String uri      = getPerforceRshURI(serverRoot)
        println uri
        IServer server  = ServerFactory.getServer(uri, null)
        server.setUserName("p4java")
        server.connect()

        IClient tempClient  = new Client()
        String clientName   = "tempClient" + UUID.randomUUID().toString().replace("-", "")
        tempClient.setName(clientName);
        tempClient.setDescription("P4Java temporary client for unit testing")
        tempClient.setRoot(clientRoot.getAbsolutePath())
        server.createClient(tempClient)
        tempClient.refresh()
        server.setCurrentClient(tempClient)

        server
    }

    @Unroll
    def "scm state for status"(Map data, String scmStatus) {
        given:

        def ltemp = File.createTempFile("P4ExportPluginSpec", "-test")
        ltemp.delete()
        def gitdir = new File(ltemp, 'scm')
        def origindir = new File(ltemp, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        def ctxt = Mock(ScmOperationContext)
        //create a p4 dir
        createPerforceServer(origindir)
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)

        def git = Git.open(gitdir)
        def path = 'testfile'
        def localfile = new File(gitdir, path)

        def revCommit = null

        if (data.mkcommit) {
            //commit the file
            localfile.createNewFile()
            localfile << 'testout'
            git.add().addFilepattern(path).call()
            //println(plugin.debugStatus(p4.status().call()))
            revCommit = git.commit().setOnly(path).setMessage('test1').setCommitter('test', 'test@example.com').call()
        }


        if (data.create) {
            localfile << 'newdata'
        } else if (data.remove) {
            localfile.delete()
        }
        def status = git.status().addPath(path).call()

        git.close()


        when:
        def result = plugin.scmStateForStatus(status, revCommit, path)
        if (ltemp.exists()) {
            FileUtils.delete(ltemp, FileUtils.RECURSIVE)
        }

        then:
        result == scmStatus


        where:
        data                           | scmStatus
        [:]                            | 'NOT_FOUND'
        [create: true]                 | 'NEW'
        [mkcommit: true, create: true] | 'MODIFIED'
        [mkcommit: true, remove: true] | 'DELETED'
    }


    def "job commit with no changes"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()
        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)

        def jobref = Stub(JobExportReference)

        def input = [:]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.JOB_COMMIT_ACTION_ID, [] as Set, [] as Set, input)

        then:
        ScmPluginException e = thrown()
        e.message == 'No changes to local p4 repo need to be exported'

    }

    def "job commit with missing commit message"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()
        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def localfile = new File(gitdir, 'blah')
        localfile << 'blah'

        def jobref = Stub(JobExportReference)
        def input = [:]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.JOB_COMMIT_ACTION_ID, [jobref] as Set, [] as Set, input)

        then:
        ScmPluginException e = thrown()
        e.message == "A ${CommitJobsAction.P_MESSAGE} is required".toString()
    }

    def "commit job no local changes"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)


        def jobref = Stub(JobExportReference)
        def input = [:]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.JOB_COMMIT_ACTION_ID, [jobref] as Set, [] as Set, input)

        then:
        ScmPluginException e = thrown()
        e.message == 'No changes to local p4 repo need to be exported'
    }

    def "commit missing jobs and paths"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def localfile = new File(gitdir, 'blah')
        localfile << 'blah'


        def input = [commitMessage: "test"]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.JOB_COMMIT_ACTION_ID, [] as Set, [] as Set, input)

        then:
        ScmPluginException e = thrown()
        e.message == 'No jobs were selected'
    }

    def "commit with deleted paths that do not exist in repo"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah', 'blah')
        def localfile = new File(gitdir, 'blah')
        localfile<<'newtext'


        def input = [message: "test"]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.JOB_COMMIT_ACTION_ID, [] as Set, ['dne'] as Set, input)

        then:
        result!=null
        result.success
        !result.id
        result.message=='No p4 changes needed'
    }
    def "commit with invalid tag should fail at validation without committing"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah-xyz.xml', 'blah')
        def localfile = new File(gitdir, 'blah-xyz.xml')
        localfile<<'newtext'


        def serializer = Mock(JobSerializer)
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'blah'
            getGroupPath() >> ''
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        def input = [message: "test", tagName: tagName]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.JOB_COMMIT_ACTION_ID, [jobref] as Set, [] as Set, input)

        then:
        ScmPluginInvalidInput e = thrown()
        e.report.errors['tagName'] == 'Tag name is not valid: ' + tagName
        0 * serializer.serialize(*_)

        where:
        tagName     | _
        'asdf asdf' | _
    }

    def "tag action with invalid tag should fail at validation"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah-xyz.xml', 'blah')
        def localfile = new File(gitdir, 'blah-xyz.xml')
//        localfile<<'newtext'

        def input = [message: "test", tagName: tagName]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.PROJECT_TAG_ACTION_ID, [] as Set, [] as Set, input)

        then:
        ScmPluginInvalidInput e = thrown()
        e.report.errors['tagName'] == 'Tag name is not valid: ' + tagName

        where:
        tagName     | _
        'asdf asdf' | _
    }
    def "push action with invalid tag should fail at validation"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def userInfo = Mock(ScmUserInfo)
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah-xyz.xml', 'blah')
        def localfile = new File(gitdir, 'blah-xyz.xml')
//        localfile<<'newtext'

        def input = [message: "test", tagName: tagName]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.PROJECT_PUSH_ACTION_ID, [] as Set, [] as Set, input)

        then:
        ScmPluginInvalidInput e = thrown()
        e.report.errors['tagName'] == 'Tag name is not valid: ' + tagName

        where:
        tagName     | _
        'asdf asdf' | _
    }
    def "commit missing user info"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir, [
                committerName : '${user.fullName}',
                committerEmail: '${user.email}',]
        )

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def userInfo = Mock(ScmUserInfo) {
            getFullName() >> userName
            getEmail() >> userEmail
        }
        def ctxt = Mock(ScmOperationContext) {
            getUserInfo() >> userInfo
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def localfile = new File(gitdir, 'blah')
        localfile << 'blah'

        def serializer = Mock(JobSerializer)
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'name'
            getGroupPath() >> 'a/b'
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        def input = [message: "Test"]
        when:
        def result = plugin.export(ctxt, P4ExportPlugin.JOB_COMMIT_ACTION_ID, [jobref] as Set, [] as Set, input)

        then:
        ScmUserInfoMissing e = thrown()
        e.fieldName == expectedMissing

        where:
        userName | userEmail | expectedMissing
        null     | null      | 'committerName'
        'bob'    | null      | 'committerEmail'
        null     | 'a@b'     | 'committerName'
    }

    def "job change delete removes file"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def ctxt = Mock(ScmOperationContext) {
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah-xyz.xml', 'blah')
        def localfile = new File(gitdir, 'blah-xyz.xml')

        def serializer = Mock(JobSerializer)
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'blah'
            getGroupPath() >> ''
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        JobChangeEvent event = Mock(JobChangeEvent) {
            getOriginalJobReference() >> jobref
            getJobReference() >> jobref
            getEventType() >> JobChangeEvent.JobChangeEventType.DELETE
        }

        when:
        def result = plugin.jobChanged(event, jobref)

        then:
        !localfile.exists()
        plugin.jobStateMap['xyz'] == null
        result != null
        result.synchState == SynchState.EXPORT_NEEDED
    }

    def "job change modify overwrites file"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def ctxt = Mock(ScmOperationContext) {
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah-xyz.xml', 'blah')
        def localfile = new File(gitdir, 'blah-xyz.xml')

        def serializer = Mock(JobSerializer) {
            1 * serialize('xml', _) >> { args ->
                args[1].write('newcontent'.bytes)
                return 10
            }
        }
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'blah'
            getGroupPath() >> ''
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        JobChangeEvent event = Mock(JobChangeEvent) {
            getOriginalJobReference() >> jobref
            getJobReference() >> jobref
            getEventType() >> theEventType
        }

        when:
        def result = plugin.jobChanged(event, jobref)

        then:
        localfile.exists()
        localfile.text == 'newcontent'
        plugin.jobStateMap['xyz'] != null
        result != null
        result.synchState == SynchState.EXPORT_NEEDED

        where:
        theEventType                             | _
        JobChangeEvent.JobChangeEventType.MODIFY | _
    }

    def "job change serializer fails does not overwrite file"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def ctxt = Mock(ScmOperationContext) {
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah-xyz.xml', 'blah')
        def localfile = new File(gitdir, 'blah-xyz.xml')

        def serializer = Mock(JobSerializer) {
            1 * serialize('xml', _) >> { args ->
                throw new IllegalArgumentException('failure')
            }
        }
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'blah'
            getGroupPath() >> ''
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        JobChangeEvent event = Mock(JobChangeEvent) {
            getOriginalJobReference() >> jobref
            getJobReference() >> jobref
            getEventType() >> theEventType
        }

        when:
        def result = plugin.jobChanged(event, jobref)

        then:
        localfile.exists()
        localfile.text == 'blah'
        plugin.jobStateMap['xyz'] != null
        result != null
        result.synchState == SynchState.CLEAN

        where:
        theEventType                             | _
        JobChangeEvent.JobChangeEventType.MODIFY | _
    }

    def "job change create creates file"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def ctxt = Mock(ScmOperationContext) {
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def localfile = new File(gitdir, 'blah-xyz.xml')

        def serializer = Mock(JobSerializer) {
            1 * serialize('xml', _) >> { args ->
                args[1].write('newcontent'.bytes)
                return 10
            }
        }
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'blah'
            getGroupPath() >> ''
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        JobChangeEvent event = Mock(JobChangeEvent) {
            getOriginalJobReference() >> jobref
            getJobReference() >> jobref
            getEventType() >> theEventType
        }

        when:
        def result = plugin.jobChanged(event, jobref)

        then:
        localfile.exists()
        localfile.text == 'newcontent'
        plugin.jobStateMap['xyz'] != null
        result != null
        result.synchState == SynchState.CREATE_NEEDED

        where:
        theEventType                             | _
        JobChangeEvent.JobChangeEventType.CREATE | _
    }

    def "job change modify-rename removes old and writes new file"() {
        given:

        def gitdir = new File(tempdir, 'scm')
        def origindir = new File(tempdir, 'origin')
        Export config = createTestConfig(gitdir, origindir)

        //create a p4 dir
        def git = createPerforceServer(origindir)
        git.close()

        def ctxt = Mock(ScmOperationContext) {
        }

        def plugin = new P4ExportPlugin(config)
        plugin.initialize(ctxt)
        def commit = addCommitFile(gitdir, plugin.git, 'blah-xyz.xml', 'blah')
        def localfile = new File(gitdir, 'blah-xyz.xml')
        def localnewfile = new File(gitdir, 'blah2-xyz.xml')

        def serializer = Mock(JobSerializer) {
            1 * serialize('xml', _) >> { args ->
                args[1].write('newcontent'.bytes)
                return 10
            }
        }
        def origref = Stub(JobExportReference) {
            getJobName() >> 'blah'
            getGroupPath() >> ''
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        def jobref = Stub(JobExportReference) {
            getJobName() >> 'blah2'
            getGroupPath() >> ''
            getId() >> 'xyz'
            getVersion() >> 1
            getJobSerializer() >> serializer
        }
        JobChangeEvent event = Mock(JobChangeEvent) {
            getOriginalJobReference() >> origref
            getJobReference() >> jobref
            getEventType() >> theEventType
        }

        when:
        def result = plugin.jobChanged(event, jobref)

        then:
        !localfile.exists()
        localnewfile.exists()
        localnewfile.text == 'newcontent'
        plugin.jobStateMap['xyz'] != null
        result != null
        result.synchState == SynchState.EXPORT_NEEDED

        where:
        theEventType                                    | _
        JobChangeEvent.JobChangeEventType.MODIFY_RENAME | _
    }
}
