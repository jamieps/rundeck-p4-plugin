package org.rundeck.plugin.scm.p4.imp.actions

import com.dtolabs.rundeck.core.plugins.configuration.PropertyValidator
import com.dtolabs.rundeck.core.plugins.configuration.ValidationException
import com.dtolabs.rundeck.core.plugins.configuration.Validator
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import org.rundeck.plugin.scm.p4.BaseAction
import org.rundeck.plugin.scm.p4.BuilderUtil
import org.rundeck.plugin.scm.p4.P4ImportAction
import org.rundeck.plugin.scm.p4.P4ImportPlugin

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Created by greg on 9/10/15.
 */
class SetupTracking extends BaseAction implements P4ImportAction {

    public static final String USE_FILE_PATTERN = "useFilePattern"
    public static final String FILE_PATTERN = "filePattern"

    SetupTracking(final String id, final String title, final String description, final String iconName) {
        super(id, title, description, iconName)
    }


    BasicInputView getInputView(final ScmOperationContext context, P4ImportPlugin plugin) {
        BuilderUtil.inputViewBuilder(id) {
            title "Setup Tracking"
            description '''Enter a Regular expression to match potential new repo files that are added.

Or, you can also choose to select a static list of Files found in the Repository to be tracked for Job Import.

Note: If you select Files and do not choose to match via regular expression,
then new files added to the repo *will not* be available for Job Import, and only those selected
files will be watched for changes.'''
            buttonTitle "Setup"
            properties([
                    BuilderUtil.property {
                        booleanType USE_FILE_PATTERN
                        title "Match a Regular Expression?"
                        description "Check to match all paths that match the regular expression."
                        required false
                        defaultValue 'true'
                        build()
                    },
                    BuilderUtil.property {
                        freeSelect FILE_PATTERN
                        title "Regular Expression"
                        description "Enter a regular expression. New paths in the repo matching this expression will also be imported."
                        required false
                        values '.*\\.xml', '.*\\.yaml'
                        defaultValue '.*\\.xml'
                        validator({ String pat ->
                            try {
                                Pattern.compile(pat)
                                return true
                            } catch (PatternSyntaxException e) {
                                throw new ValidationException("Invalid regular expression: " + e.message)
                            }
                                  } as PropertyValidator
                        )
                    },
            ]
            )
        }
    }

    /**
     * Add config values for inputs
     * @param plugin
     * @param selectedPaths
     * @param input
     */
    static void setupWithInput(
            final P4ImportPlugin plugin,
            final List<String> selectedPaths,
            final Map<String, String> input
    )
    {
        if (input[USE_FILE_PATTERN] != null && selectedPaths != null) {
            P4ImportPlugin.log.debug("SetupTracking: ${selectedPaths}, ${input} (true)")
            plugin.trackedItems = selectedPaths
            plugin.useTrackingRegex = 'true' == input[USE_FILE_PATTERN]
            plugin.trackingRegex = plugin.useTrackingRegex ? input[FILE_PATTERN] : null
            plugin.trackedItemsSelected = true
        } else {
            P4ImportPlugin.log.debug("SetupTracking: ${selectedPaths}, ${input} (false)")
            plugin.trackedItemsSelected = false
        }
    }

    @Override
    ScmExportResult performAction(
            final ScmOperationContext context,
            final P4ImportPlugin plugin,
            final JobImporter importer,
            final List<String> selectedPaths,
            final Map<String, String> input
    )
    {
        if (input[USE_FILE_PATTERN] != 'true' && !selectedPaths) {
            throw new ScmPluginInvalidInput(
                    Validator.errorReport(
                            USE_FILE_PATTERN,
                            "If no static paths are selected, then you must enter a regular expression."
                    )
            )
        }
        if (input[USE_FILE_PATTERN] == 'true' && !input[FILE_PATTERN]) {
            throw new ScmPluginInvalidInput(
                    Validator.errorReport(
                            FILE_PATTERN,
                            "If no static paths are selected, then you must enter a regular expression."
                    )
            )
        }
        setupWithInput(plugin, selectedPaths, input)

        def result = new ScmExportResultImpl()
        result.success = true
        result.message = "Setup successful"

        result
    }

}
