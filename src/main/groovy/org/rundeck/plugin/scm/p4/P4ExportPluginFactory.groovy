package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.plugins.Plugin
import com.dtolabs.rundeck.core.plugins.configuration.Describable
import com.dtolabs.rundeck.core.plugins.configuration.Description
import com.dtolabs.rundeck.core.plugins.configuration.Property
import com.dtolabs.rundeck.plugins.ServiceNameConstants
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription
import com.dtolabs.rundeck.plugins.scm.ScmExportPlugin
import com.dtolabs.rundeck.plugins.scm.ScmExportPluginFactory
import com.dtolabs.rundeck.plugins.scm.ScmOperationContext
import org.rundeck.plugin.scm.p4.config.Common
import org.rundeck.plugin.scm.p4.config.Config
import org.rundeck.plugin.scm.p4.config.Export

import static BuilderUtil.pluginDescription

/**
 * Factory for Perforce export plugin
 */
@Plugin(name = P4ExportPluginFactory.PROVIDER_NAME, service = ServiceNameConstants.ScmExport)
@PluginDescription(title = P4ExportPluginFactory.TITLE, description = P4ExportPluginFactory.DESC)
class P4ExportPluginFactory implements ScmExportPluginFactory, Describable {
    static final String PROVIDER_NAME = 'p4-export'
    public static final String DESC = "Export Jobs to a Perforce Repository"
    public static final String TITLE = "Perforce Export"

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
        Common.addDirDefaultValue(getSetupProperties(), basedir)
    }


    static List<Property> getSetupProperties() {
        Config.listProperties(Export)
    }

    @Override
    ScmExportPlugin createPlugin(final ScmOperationContext context, final Map<String, String> input) {
        def config = Config.create(Export, input)
        def plugin = new P4ExportPlugin(config)
        plugin.initialize(context)
        return plugin
    }
}
