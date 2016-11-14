package org.rundeck.plugin.scm.p4.exp.actions

import com.dtolabs.rundeck.core.plugins.configuration.Validator
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.core.ILabel
import org.rundeck.plugin.scm.p4.*

import java.util.regex.Pattern

/**
 * Create a tag
 */
class LabelAction extends BaseAction implements P4ExportAction {

    public static final String P_DESCRIPTION = 'message'
    public static final String P_LABEL_NAME = 'labelName'
    public static final Pattern P4_VALID_LABEL_REGEX = Pattern.compile("^[A-Za-z0-9-.]+\$")

    LabelAction(final String id, final String title, final String description) {
        super(id, title, description)
    }

    BasicInputView getInputView(final ScmOperationContext context, P4ExportPlugin plugin) {
        BuilderUtil.inputViewBuilder(id) {
            title "Commit Changes to Perforce"
            buttonTitle "Commit"
            properties([
                    BuilderUtil.property {
                        string P_DESCRIPTION
                        title "Description"
                        description "Enter a description for the label."
                        required true
                        renderingAsTextarea()
                    },

                    BuilderUtil.property {
                        string P_LABEL_NAME
                        title "Label"
                        description "Enter a label name to include."
                        required true
                    },

            ]
            )
        }
    }

    @Override
    ScmExportResult perform(
            final P4ExportPlugin plugin,
            final Set<JobExportReference> jobs,
            final Set<String> pathsToDelete,
            final ScmOperationContext context,
            final Map<String, String> input
    ) throws ScmPluginException
    {
        if (!input[P_LABEL_NAME]) {
            throw new ScmPluginInvalidInput(
                    Validator.errorReport(P_LABEL_NAME, "Label name is required.")
            )
        }
        if (!input[P_DESCRIPTION]) {
            throw new ScmPluginInvalidInput(
                    Validator.errorReport(P_DESCRIPTION, "Description required for a new label.")
            )
        }
        validateLabelDoesNotExist( plugin, input[P_LABEL_NAME])
        validateLabelName(input[P_LABEL_NAME])
        def commit = plugin.getHead()
        ILabel label

        try {
            label = P4Util.createLabel(plugin.p4Server, input[P_LABEL_NAME], input[P_DESCRIPTION],
                    plugin.getConfig().getP4Spec(), commit)
        } catch (Exception e) {
            plugin.logger.debug("Failed create tag: ${e.message}", e)
            throw new ScmPluginException("Failed create tag: ${e.message}", e)
        }
        def result = new ScmExportResultImpl()
        result.success = true
        result.message = "Created label: ${label.getName()}"
        return result
    }

    static void validateLabelDoesNotExist(P4ExportPlugin plugin, String labelName) {
        def found = plugin.p4Server.getLabel(labelName)
        if (found != null) {
            throw new ScmPluginInvalidInput(
                    Validator.errorReport(P_LABEL_NAME, "Tag already exists: ${labelName}")
            )
        }
    }
    static void validateLabelName(String labelName) {
        boolean valid = P4_VALID_LABEL_REGEX.matcher(labelName).matches()
        if (!valid) {
            throw new ScmPluginInvalidInput(Validator.errorReport(P_LABEL_NAME,
                    "Label name is not valid: ${labelName}"))
        }
    }
}
