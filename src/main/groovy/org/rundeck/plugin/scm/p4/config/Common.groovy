package org.rundeck.plugin.scm.p4.config

import com.dtolabs.rundeck.core.plugins.configuration.Property
import com.dtolabs.rundeck.core.plugins.configuration.PropertyValidator
import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants
import com.dtolabs.rundeck.core.plugins.configuration.ValidationException
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty
import com.dtolabs.rundeck.plugins.descriptions.RenderingOption
import com.dtolabs.rundeck.plugins.descriptions.RenderingOptions
import com.dtolabs.rundeck.plugins.descriptions.SelectValues

import java.util.regex.Pattern

/**
 * Common configuration class
 */
class Common extends Config {
    @PluginProperty(
            title = "Base Directory",
            description = "Directory for checkout",
            required = true
    )
    String dir

    static class PathTemplateValidator implements PropertyValidator {
        @Override
        boolean isValid(final String value) throws ValidationException {
            value ==~ ('^.*' + Pattern.quote('${job.id}') + '.*$')
        }
    }

    @PluginProperty(
            title = "File Path Template",
            description = '''Path template for storing a Job to a file within the base dir.

Available expansion patterns:

* `${job.name}` - the job name
* `${job.group}` - blank, or `path/`
* `${job.project} - project name`
* `${job.id}` - job UUID (this value *should* be included in the template to guarantee a unique path for each job.)
* `${config.format}` - Serialization format chosen below.
''',
            defaultValue = '${job.group}${job.name}-${job.id}.${config.format}',
            required = true
    )
    String pathTemplate

    @PluginProperty(
            title = "Perforce URI",
            description = '''Perforce server URI to use.

E.g. `p4java://localhost:1666?userName=foo.bar&clientName=test&autoConnect=y`''',
            required = true
    )
    String uri

    @PluginProperty(
            title = "Perforce client",
            description = '''The name of the Perforce client workspace to use.

If it does not exist, it will be created, the client view will match the spec specified below.''',
            required = true
    )
    String p4ClientName

    @PluginProperty(
            title = "Perforce spec",
            description = '''Perforce spec.

See [File Specifications](https://www.perforce.com/perforce/doc.current/manuals/cmdref/filespecs.html)

Some examples:

* `//depot/path/...`''',
            required = true
    )
    String p4Spec

    @PluginProperty(

            title = "SSH Key Storage Path",
            description = '''Path can include variable references

* `${user.login}` login name of logged in user
* `${project}` current project name'''
    )
    @RenderingOptions(
            [
                    @RenderingOption(
                            key = StringRenderingConstants.SELECTION_ACCESSOR_KEY,
                            value = 'STORAGE_PATH'
                    ),
                    @RenderingOption(
                            key = StringRenderingConstants.STORAGE_PATH_ROOT_KEY,
                            value = 'keys'
                    ),
                    @RenderingOption(
                            key = StringRenderingConstants.STORAGE_FILE_META_FILTER_KEY,
                            value = "Rundeck-key-type=private"
                    )
            ]
    )
    String sshPrivateKeyPath

    @PluginProperty(
            title = 'Password Storage Path',
            description = '''Password to authenticate remotely (e.g. for SSH or HTTPS URLs).

Path can include variable references

* `${user.login}` login name of logged in user
* `${project}` current project name'''
    )
    @RenderingOptions(
            [
                    @RenderingOption(
                            key = StringRenderingConstants.SELECTION_ACCESSOR_KEY,
                            value = 'STORAGE_PATH'
                    ),
                    @RenderingOption(
                            key = StringRenderingConstants.STORAGE_PATH_ROOT_KEY,
                            value = 'keys'
                    ),
                    @RenderingOption(
                            key = StringRenderingConstants.STORAGE_FILE_META_FILTER_KEY,
                            value = "Rundeck-data-type=password"
                    )
            ]
    )
    String gitPasswordPath

    @PluginProperty(
            title = "Format",
            description = "Format for serializing Job definitions",
            defaultValue = 'xml',
            required = true
    )
    @SelectValues(values = ['xml', 'yaml'])
    String format

    @PluginProperty(
            title = "Fetch Automatically",
            description = "Automatically fetch remote changes for local comparison. If false, you can perform it manually",
            defaultValue = 'true',
            required = false
    )
    @SelectValues(values = ['true', 'false'])
    String fetchAutomatically

    boolean shouldFetchAutomatically(){
        return fetchAutomatically in [null, 'true']
    }


    static List<Property> addDirDefaultValue(List<Property> properties, File basedir) {
        if (null == basedir) {
            return properties
        }
        substituteDefaultValue properties, 'dir', new File(basedir, 'scm').absolutePath
    }
}
