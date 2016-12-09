package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.plugins.scm.ScmOperationContext
import spock.lang.Specification

/**
 * Created by greg on 8/31/15.
 */
class P4ExportPluginFactorySpec extends Specification {

    File tempdir

    def setup() {
        tempdir = File.createTempFile("P4ExportPluginFactorySpec", "-test")
        tempdir.delete()
    }

    def cleanup() {
        if (tempdir.exists()) {
            tempdir.deleteDir()
        }
    }

    def "base description"() {
        given:
        def factory = new P4ExportPluginFactory()
        def desc = factory.description

        expect:
        desc.title == 'Perforce Export'
        desc.name == 'p4-export'
        desc.properties.size() == 11
    }

    def "base description properties"() {
        given:
        def factory = new P4ExportPluginFactory()
        def desc = factory.description

        expect:
        desc.properties*.name as Set == [
                'committerName',
                'committerEmail',
                'dir',
                'pathTemplate',
                'uri',
                'p4ClientName',
                'p4Spec',
                'sshPrivateKeyPath',
                'gitPasswordPath',
                'format',
                'fetchAutomatically',
        ] as Set
    }

    def "setup properties without basedir"() {
        given:
        def factory = new P4ExportPluginFactory()
        def properties = factory.getSetupProperties()

        expect:
        properties*.name as Set == [
                'committerName',
                'committerEmail',
                'dir',
                'pathTemplate',
                'uri',
                'p4ClientName',
                'p4Spec',
                'sshPrivateKeyPath',
                'gitPasswordPath',
                'format',
                'fetchAutomatically',
        ] as Set
        def dirprop = properties.find { it.name == 'dir' }
        dirprop.defaultValue == null

    }

    def "setup properties with basedir"() {
        given:
        def factory = new P4ExportPluginFactory()
        def tempdir = File.createTempFile("blah", "test")
        tempdir.deleteOnExit()
        tempdir.delete()
        def properties = factory.getSetupPropertiesForBasedir(tempdir)

        expect:
        properties*.name  as Set == [
                'dir',
                'pathTemplate',
                'uri',
                'p4ClientName',
                'p4Spec',
                'sshPrivateKeyPath',
                'gitPasswordPath',
                'format',
                'fetchAutomatically',
                'committerName',
                'committerEmail',
        ] as Set
        properties.find { it.name == 'dir' }.defaultValue == new File(tempdir.absolutePath, 'scm').absolutePath
    }

    def "create plugin"() {
        given:

        def factory = new P4ExportPluginFactory()
        def scmDir = new File(tempdir, 'scm')
        def p4ServerRoot = new File(tempdir, 'p4root')
        Map<String, String> config = [
                dir                  : scmDir.absolutePath,
                pathTemplate         : '${job.group}${job.name}-${job.id}.xml',
                branch               : 'master',
                committerName        : 'test user',
                committerEmail       : 'test@example.com',
                strictHostKeyChecking: 'yes',
                format               : 'xml',
                uri                  : P4ExportPluginSpec.getPerforceRshURI(p4ServerRoot),
                p4ClientName         : 'foobar',
                p4Spec               : '//depot/foo/...',
        ]

        // create a p4 dir
        def p4Server = P4Utils.getPerforceServer(P4Utils.createPerforceServer(p4ServerRoot))
        def p4Client = P4Utils.createPerforceClient(scmDir)
        def ctxt = Mock(ScmOperationContext) {
        }

        when:
        def plugin = factory.createPlugin(ctxt, config)

        then:
        null != plugin
        p4ServerRoot.isDirectory()
    }
}
