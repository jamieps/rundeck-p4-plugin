package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.core.plugins.configuration.Describable
import com.dtolabs.rundeck.core.plugins.configuration.Description
import com.dtolabs.rundeck.core.plugins.configuration.Property
import com.dtolabs.rundeck.plugins.ServiceNameConstants
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription
import com.dtolabs.rundeck.plugins.scm.ScmImportPlugin
import com.dtolabs.rundeck.plugins.scm.ScmImportPluginFactory
import com.dtolabs.rundeck.plugins.scm.ScmOperationContext
import org.rundeck.plugin.scm.p4.config.Common
import org.rundeck.plugin.scm.p4.config.Config
import org.rundeck.plugin.scm.p4.config.Import

import static BuilderUtil.pluginDescription

/**
 * Created by greg on 9/9/15.
 */
@Plugin(name = P4ImportPluginFactory.PROVIDER_NAME, service = ServiceNameConstants.ScmImport)
@PluginDescription(title = P4ImportPluginFactory.TITLE, description = P4ImportPluginFactory.DESC)
class P4ImportPluginFactory implements ScmImportPluginFactory, Describable {
    static final String PROVIDER_NAME = 'p4-import'
    public static final String DESC = "Import Jobs from a Perforce Repository"
    public static final String TITLE = "Perforce Import"


    @Override
    Description getDescription() {
        pluginDescription {
            name PROVIDER_NAME
            title TITLE
            description DESC
            def del = delegate
            setupProperties.each {
                del.property it
            }
        }
    }

    List<Property> getSetupPropertiesForBasedir(File basedir) {
        Common.addDirDefaultValue setupProperties, basedir
    }


    static List<Property> getSetupProperties() {
        Config.listProperties Import
    }

    @Override
    ScmImportPlugin createPlugin(
            final ScmOperationContext context,
            final Map<String, String> input,
            final List<String> trackedItems
    )
    {
        def config = Config.create Import, input
        def plugin = new P4ImportPlugin(config, trackedItems)
        plugin.initialize context
        return plugin
    }
}
