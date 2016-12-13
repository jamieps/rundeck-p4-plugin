package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.client.IClient
import com.perforce.p4java.core.file.FileSpecOpStatus
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.server.IServer
import org.rundeck.plugin.scm.p4.config.Common
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Created by greg on 10/14/15.
 */
class BaseP4PluginSpec extends Specification {
    File tempdir

    def setup() {
        tempdir = File.createTempDir("BaseP4PluginSpec", "-test")
    }

    def cleanup() {
        if (tempdir.exists()) {
            tempdir.deleteDir()
        }
    }

    def "serialize job to valid file path"() {
        given:
        Common config = new Common()
        def base = new BaseP4Plugin(config)
        base.mapper = Mock(JobFileMapper)
        def job = Mock(JobExportReference){
            getVersion() >> 1L
        }
        def outfile = File.createTempFile("BaseP4PluginSpec", "serialize-job.temp")
        outfile.deleteOnExit()

        when:
        base.serialize(job, format)

        then:
        1 * base.mapper.fileForJob(_) >> outfile
        1 * job.getJobSerializer() >> Mock(JobSerializer) {
            1 * serialize(format, !null)>>{args->
                args[1].write('data'.bytes)
            }
        }
        outfile.text=='data'


        where:
        format | _
        'xml'  | _
        'yaml' | _
    }

    def "serialize job with two threads will not write same revision twice"() {
        given:
        Common config = new Common()
        def base = new BaseP4Plugin(config)
        base.mapper = Mock(JobFileMapper)
        def job = Mock(JobExportReference){
            getVersion() >> 1L
        }
        def job2 = Mock(JobExportReference){
            getVersion() >> 1L
        }
        def outfile = File.createTempFile("BaseP4PluginSpec", "serialize-job.temp")
        outfile.deleteOnExit()

        AtomicLong counter = new AtomicLong(-1)
        base.fileSerializeRevisionCounter[outfile]=counter
        AtomicLong serializedCounter=new AtomicLong(0)

        _ * job.getJobSerializer() >> Mock(JobSerializer) {
            _ * serialize(format, !null)>>{args->
                serializedCounter.incrementAndGet()
                args[1].write('data'.bytes)
            }
        }
        _ * job2.getJobSerializer() >> Mock(JobSerializer) {
            _ * serialize(format, !null)>>{args->
                serializedCounter.incrementAndGet()
                args[1].write('data2'.bytes)
            }
        }
        when:
        def latch = new CountDownLatch(2)
        synchronized (counter){
            // grab lock on counter
            // now start threads which will block at the synchronized block
            def t1 = Thread.start {
                base.serialize(job, format)
                latch.countDown()
            }
            def t2 = Thread.start {
                base.serialize(job2, format)
                latch.countDown()
            }
        }
        // wait until they are done
        latch.await()

        then:
        2 * base.mapper.fileForJob(_) >> outfile

        1L == serializedCounter.longValue()


        where:
        format | _
        'xml'  | _
        'yaml' | _
    }
    def "serialize job with two revision will not write older revision"() {
        given:
        Common config = new Common()
        def base = new BaseP4Plugin(config)
        base.mapper = Mock(JobFileMapper)
        def job = Mock(JobExportReference){
            getVersion() >> 1L
        }
        def newerJob = Mock(JobExportReference){
            getVersion() >> 2L
        }
        def outfile = File.createTempFile("BaseP4PluginSpec", "serialize-job.temp")
        outfile.deleteOnExit()

        AtomicLong counter = new AtomicLong(-1)
        base.fileSerializeRevisionCounter[outfile]=counter
        AtomicLong serializedCounter=new AtomicLong(0)

        _ * job.getJobSerializer() >> Mock(JobSerializer) {
            0 * serialize(format, !null) >> { args->
                serializedCounter.incrementAndGet()
                args[1].write('data'.bytes)
            }
        }
        _ * newerJob.getJobSerializer() >> Mock(JobSerializer) {
            1 * serialize(format, !null) >> { args->
                serializedCounter.incrementAndGet()
                args[1].write('data2'.bytes)
            }
        }
        when:

        base.serialize(newerJob, format)

        base.serialize(job, format)

        then:
        2 * base.mapper.fileForJob(_) >> outfile

        1L == serializedCounter.longValue()


        where:
        format | _
        'xml'  | _
        'yaml' | _
    }

    def "serialize job: cannot create parent dir"() {
        given:
        Common config = new Common()
        def base = new BaseP4Plugin(config)
        base.mapper = Mock(JobFileMapper)
        def job = Mock(JobExportReference){
            getVersion() >> 1L
        }
        def outfile1 = File.createTempFile("BaseP4PluginSpec", "serialize-job.temp")
        outfile1.deleteOnExit()

        //parent dir is actually a file, so mkdirs() will fail
        def outfile = new File(outfile1, "test")


        when:
        base.serialize(job, format)

        then:
        1 * base.mapper.fileForJob(_) >> outfile
        ScmPluginException e = thrown()
        e.message.startsWith('Cannot create necessary dirs to serialize file to path')

        where:
        format | _
        'xml'  | _
        'yaml' | _
    }

    def "serialize job: no output"() {
        given:
        Common config = new Common()
        def base = new BaseP4Plugin(config)
        base.mapper = Mock(JobFileMapper)
        def job = Mock(JobExportReference){
            getVersion()>>1L
        }
        def outfile = File.createTempFile("BaseP4PluginSpec", "serialize-job.temp")
        outfile.deleteOnExit()

        when:
        base.serialize(job, format)

        then:
        1 * base.mapper.fileForJob(_) >> outfile
        1 * job.getJobSerializer() >> Mock(JobSerializer) {
            1 * serialize(format, !null) // no write to stream
        }
        ScmPluginException e = thrown()
        e.message.startsWith('Failed to serialize job, no content was written')

        where:
        format | _
        'xml'  | _
        'yaml' | _
    }

    def "serialize job: IO exception"() {
        given:
        Common config = new Common()
        def base = new BaseP4Plugin(config)
        base.mapper = Mock(JobFileMapper)
        def job = Mock(JobExportReference) {
            getVersion() >> 1L
        }
        def outfile = File.createTempFile("BaseP4PluginSpec", "serialize-job.temp")
        outfile.deleteOnExit()

        when:
        base.serialize(job, format)

        then:
        1 * base.mapper.fileForJob(_) >> outfile
        1 * job.getJobSerializer() >> Mock(JobSerializer) {
            1 * serialize(format, !null) >> {
                throw new IOException("test forced error")
            }
        }
        ScmPluginException e = thrown()
        e.message.startsWith('Failed to serialize job')
        e.message.endsWith('test forced error')

        where:
        format | _
        'xml'  | _
        'yaml' | _
    }


    def "serialize temp"() {
        given:
        Common config = new Common()
        def base = new BaseP4Plugin(config)
        base.mapper = Mock(JobFileMapper)
        def job = Mock(JobExportReference){
            getVersion()>>1L
        }

        when:
        def outfile = base.serializeTemp(job, format)


        then:
        1 * job.getJobSerializer() >> Mock(JobSerializer) {
            1 * serialize(format, !null)
        }
        outfile != null
        outfile.isFile()


        where:
        format | _
        'xml'  | _
        'yaml' | _
    }

    def "sync with no files"() {
        given:
        def scmDir = new File(tempdir, 'scm')
        // create a p4 dir
        File tmpServerRoot = File.createTempDir("BaseP4PluginSpec", "-test")
        tmpServerRoot.deleteOnExit()

        IServer p4Server = P4Utils.getPerforceServer(P4Utils.createPerforceServer(tmpServerRoot))
        IClient p4Client = P4Utils.createPerforceClient(p4Server, scmDir)

        Common config = new Common()
        config.p4ClientName = p4Client.getName()

        def base = new BaseP4Plugin(config)
        base.p4Server = p4Server
        base.p4Client = p4Client

        when:
        List<IFileSpec> update = base.sync(Mock(ScmOperationContext))
        update.each { IFileSpec spec ->
            println "${spec.getDepotPath()} ${spec.getOpStatus()} ${spec.getStatusMessage()} ${spec.getDate()}"
        }

        then:
        update[0].getOpStatus() == FileSpecOpStatus.ERROR
        update[0].getStatusMessage() =~ ".* - no such file\\(s\\)."
    }

    def "sync with changes"() {
        given:
        def scmDir1 = File.createTempDir("BaseP4PluginSpec", "-scm")
        scmDir1.deleteOnExit()
        def scmDir2 = File.createTempDir("BaseP4PluginSpec", "-scm")
        scmDir2.deleteOnExit()
        def tmpServerRoot = File.createTempDir("BaseP4PluginSpec", "-test")
        tmpServerRoot.deleteOnExit()

        IServer p4Server = P4Utils.getPerforceServer(P4Utils.createPerforceServer(tmpServerRoot))
        IClient p4Client = P4Utils.createPerforceClient(p4Server, scmDir1)
        def commit1 = P4ExportPluginSpec.addCommitFile(scmDir1, p4Client,
                'testFile', 'We have no time to stand and stare')
        def commit2 = P4ExportPluginSpec.addCommitFile(scmDir1, p4Client,
                'testFile', 'What is this life if, full of care')

        IClient p4Client2 = P4Utils.createPerforceClient(p4Server, scmDir2)
        Common config = new Common()
        config.p4ClientName = p4Client2.getName()
        def base = new BaseP4Plugin(config)
        base.p4Server = p4Server
        base.p4Client = p4Client2

        when:
        def update = base.sync(Mock(ScmOperationContext))

        then:
        commit1 != null
        commit2 != null
        p4Client != null
        update != null
        update.get(0).getChangelistId() == 2
        update.get(0).getClientPathString() == scmDir2.getAbsolutePath() + File.separator + "testFile"
    }

    def "expand user string"() {
        given:
        def userinfo = Stub(ScmUserInfo) {
            getUserName() >> 'Z'
            getFirstName() >> 'A'
            getLastName() >> 'B'
            getFullName() >> 'A B'
            getEmail() >> 'c@d.e'
        }

        expect:
        BaseP4Plugin.expand(input, userinfo) == result

        where:
        input                                                                           | result
        'Blah'                                                                          | 'Blah'
        '${user.userName}'                                                              | 'Z'
        '${user.login}'                                                                 | 'Z'
        '${user.fullName}'                                                              | 'A B'
        '${user.firstName}'                                                             | 'A'
        '${user.lastName}'                                                              | 'B'
        '${user.email}'                                                                 | 'c@d.e'
        'Bob ${user.firstName} x ${user.lastName} y ${user.email} H ${user.userName} I' | 'Bob A x B y c@d.e H Z I'
    }

    def "expand context vars in path"() {
        given:
        def userinfo = Stub(ScmUserInfo) {
            getUserName() >> 'Z'
            getFirstName() >> 'A'
            getLastName() >> 'B'
            getFullName() >> 'A B'
            getEmail() >> 'c@d.e'
        }
        def context = Stub(ScmOperationContext) {
            getUserInfo() >> userinfo
            getFrameworkProject() >> 'testproject'
        }

        expect:
        BaseP4Plugin.expandContextVarsInPath(context, path) == result

        where:
        path                           | result
        'a/b/c'                        | 'a/b/c'
        'a/${user.login}/c'            | 'a/Z/c'
        'a/${user.userName}/c'         | 'a/Z/c'
        'a/${user.firstName}/c'        | 'a/A/c'
        'a/${user.lastName}/c'         | 'a/B/c'
        'a/${user.fullName}/c'         | 'a/A B/c'
        'a/${user.email}/c'            | 'a/c@d.e/c'
        'a/${project}/c'               | 'a/testproject/c'
        'a/${project}/${user.login}/c' | 'a/testproject/Z/c'
    }

    def "expand user missing info"() {
        given:
        def userinfo = Stub(ScmUserInfo) {
        }

        expect:
        BaseP4Plugin.expand(input, userinfo) == result

        where:
        input                                                                           | result
        'Blah'                                                                          | 'Blah'
        '${user.userName}'                                                              | ''
        '${user.fullName}'                                                              | ''
        '${user.firstName}'                                                             | ''
        '${user.lastName}'                                                              | ''
        '${user.email}'                                                                 | ''
        'Bob ${user.firstName} x ${user.lastName} y ${user.email} H ${user.userName} I' | 'Bob  x  y  H  I'
    }

}
