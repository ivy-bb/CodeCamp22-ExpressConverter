package ch.ivyteam.ivy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ch.ivy.addon.portalkit.bo.ExpressFormElement;
import ch.ivy.addon.portalkit.bo.ExpressProcess;
import ch.ivy.addon.portalkit.bo.ExpressTaskDefinition;
import ch.ivy.addon.portalkit.persistence.converter.BusinessEntityConverter;
import ch.ivyteam.ivy.application.IApplication;
import ch.ivyteam.ivy.application.IProcessModel;
import ch.ivyteam.ivy.application.IProcessModelVersion;
import ch.ivyteam.ivy.components.ProcessKind;
import ch.ivyteam.ivy.dialog.configuration.DialogCreationParameters;
import ch.ivyteam.ivy.dialog.configuration.IUserDialogManager;
import ch.ivyteam.ivy.process.IProcess;
import ch.ivyteam.ivy.process.IProcessManager;
import ch.ivyteam.ivy.process.IProjectProcessManager;
import ch.ivyteam.ivy.process.model.diagram.Diagram;
import ch.ivyteam.ivy.process.model.diagram.shape.DiagramShape;
import ch.ivyteam.ivy.process.model.element.activity.UserTask;
import ch.ivyteam.ivy.process.model.element.activity.value.dialog.UserDialogId;
import ch.ivyteam.ivy.process.model.element.activity.value.dialog.UserDialogStart;
import ch.ivyteam.ivy.process.model.element.event.end.TaskEnd;
import ch.ivyteam.ivy.process.model.element.event.start.RequestStart;
import ch.ivyteam.ivy.process.model.element.event.start.value.CallSignature;
import ch.ivyteam.ivy.process.model.element.event.start.value.StartAccessPermissions;
import ch.ivyteam.ivy.process.model.element.gateway.TaskSwitchGateway;
import ch.ivyteam.ivy.process.model.element.value.CaseConfig;
import ch.ivyteam.ivy.process.model.element.value.IvyScriptExpression;
import ch.ivyteam.ivy.process.model.element.value.Mapping;
import ch.ivyteam.ivy.process.model.element.value.task.Activator;
import ch.ivyteam.ivy.process.model.element.value.task.ActivatorType;
import ch.ivyteam.ivy.process.model.element.value.task.CustomField;
import ch.ivyteam.ivy.process.model.element.value.task.TaskConfig;
import ch.ivyteam.ivy.process.model.element.value.task.TaskConfigs;
import ch.ivyteam.ivy.process.model.element.value.task.TaskIdentifier;
import ch.ivyteam.ivy.process.model.value.MappingCode;
import ch.ivyteam.ivy.process.model.value.scripting.VariableDesc;
import ch.ivyteam.ivy.process.resource.ProcessCreator;
import ch.ivyteam.ivy.resource.datamodel.ResourceDataModelException;
import ch.ivyteam.util.StringUtil;

@SuppressWarnings("restriction")
public class ExpressWorkflowImporter0 {

	static final int GRID_X = 128;
	static final int GRID_Y = 96;
	static final String NAMESPACE = "axon.ivy.express.export.";

	public static void importJson(String json) throws IOException, ResourceDataModelException {
		Gson gson = new GsonBuilder().serializeNulls().create();
		JsonObject jsonObject = gson.fromJson(json, JsonObject.class);

		// Ivy.log().debug("Version {0}", jsonObject.get("version").getAsInt());
		JsonElement workflowsElement = jsonObject.get("expressWorkflow");
		List<ExpressProcess> expressProcessEntities = BusinessEntityConverter
				.jsonValueToEntities(workflowsElement.toString(), ExpressProcess.class);
		for (ExpressProcess expressProcess : expressProcessEntities) {
			convert(expressProcess);
		}
	}

	public static void convert(ExpressProcess expressProcess) throws ResourceDataModelException, IOException {
		IProcessModel pm = IApplication.current().findProcessModel("AxonIvyExpressExport");
		IProcessModelVersion pmv = pm.getReleasedProcessModelVersion();
		IProject project = pmv.getProject();
		writeProcess(expressProcess, project);
	}

	private static void writeProcess(ExpressProcess expressProcess, IProject project)
			throws ResourceDataModelException, IOException {

		IProjectProcessManager manager = IProcessManager.instance().getProjectDataModelFor(project);
		IProcess process = manager.findProcessByPath("Business Processes/" + expressProcess.getProcessName(), false);

		if (process != null) {
			throw new ResourceDataModelException("Already existing " + process);
		}
		List<VariableDesc> dataFields = new ArrayList<VariableDesc>();
		String processName = StringUtil.toJavaIdentifier(expressProcess.getProcessName());
		String dataclassName = NAMESPACE + processName + "Data";

		createDialogs(expressProcess.getTaskDefinitions(), project, dataFields, dataclassName);

		/*
		 * ProcessCreationParameters createParameters = ProcessCreator .create(project,
		 * processName) .kind(ProcessKind.NORMAL) .namespace("")
		 * .dataClassName(dataclassName) .createDefaultContent(true) // ??? data class
		 * not generated. Why??? .dataClassFields(dataFields)
		 * .toCreator().getCreateParameters(); process =
		 * manager.createProcess(createParameters, new NullProgressMonitor());
		 */

		ProcessCreator creator = ProcessCreator.create(project, processName).kind(ProcessKind.NORMAL).namespace("")
				.dataClassName(dataclassName).createDefaultContent(false) // ??? data class not generated. Why???
				.dataClassFields(dataFields).toCreator();

		creator.createDataModel(new NullProgressMonitor());
		process = creator.getCreatedProcess();

		Diagram diagram = process.getModel().getDiagram();

		drawElements(expressProcess.getTaskDefinitions(), diagram, expressProcess.getProcessName(), dataclassName,
				manager);

		process.save();
		refreshTree(project);
	}

	private static void createDialogs(List<ExpressTaskDefinition> tasks, IProject project,
			List<VariableDesc> dataFields, String dataclassName) throws ResourceDataModelException, IOException {
		for (ExpressTaskDefinition taskdef : tasks) {
			writeJSFDialog(taskdef, project, dataFields, dataclassName);
		}
	}

	private static void writeJSFDialog(ExpressTaskDefinition taskdef, IProject project, List<VariableDesc> dataFields,
			String dataclassName) throws ResourceDataModelException, IOException {

		StringBuffer headerPanel = new StringBuffer();
		StringBuffer leftPanel = new StringBuffer();
		StringBuffer rightPanel = new StringBuffer();
		StringBuffer footerPanel = new StringBuffer();

		List<VariableDesc> dialogDataFields = new ArrayList<VariableDesc>();

		for (ExpressFormElement formElement : taskdef.getFormElements()) {
			String datafield = StringUtil.toJavaIdentifier(formElement.getLabel());
			if (formElement.getElementType().equals("ManyCheckbox")) {
				dialogDataFields
						.add(new VariableDesc(datafield, "ch.ivyteam.ivy.scripting.objects.List<java.lang.String>"));
			} else {
				dialogDataFields.add(new VariableDesc(datafield, "java.lang.String"));
			}

			switch (formElement.getElementPosition()) {
			case "HEADER":
				createFormElement(headerPanel, formElement, datafield);
				break;
			case "LEFTPANEL":
				createFormElement(leftPanel, formElement, datafield);
				break;
			case "RIGHTPANEL":
				createFormElement(rightPanel, formElement, datafield);
				break;
			case "FOOTER":
				createFormElement(footerPanel, formElement, datafield);
			}
		}

		dataFields.addAll(dialogDataFields);

		InputStream is = ExpressWorkflowImporter.class.getResourceAsStream("/resources/dialog_template.xhtml");
		String template = new String(is.readAllBytes());
		template = template.replace("${headerpanelfields}", headerPanel.toString());
		template = template.replace("${leftpanelfields}", leftPanel.toString());
		template = template.replace("${rightpanelfields}", rightPanel.toString());
		template = template.replace("${footerpanelfields}", footerPanel.toString());
		template = template.replace("${tasktitle}", taskdef.getSubject());
		template = template.replace("${taskdescription}", "" + taskdef.getDescription());
		is.close();

		List<VariableDesc> inputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		List<VariableDesc> outputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		CallSignature dlgCallSigature = new CallSignature("start", inputParameters, outputParameters);

		/*List<Mapping> paramMappings = new ArrayList<Mapping>();
		for(VariableDesc var: dialogDataFields)
		{
			paramMappings.add(new Mapping("out."+var.getName(), "param.data." + var.getName()));
		}
		List<Mapping> resultMappings = new ArrayList<Mapping>();
		for(VariableDesc var: dialogDataFields)
		{
			resultMappings.add(new Mapping("result.data."+var.getName(), "in." + var.getName()));
		}
		*/
		
		dialogDataFields = Arrays.asList(new VariableDesc("data", dataclassName));
		List<Mapping> paramMappings = Arrays.asList(new Mapping("out.data", "param.data"));
		List<Mapping> resultMappings = Arrays.asList(new Mapping("result.data", "in.data"));
		
		DialogCreationParameters params = new DialogCreationParameters.Builder(project,
				NAMESPACE + StringUtil.toJavaIdentifier(taskdef.getSubject())).viewContent(template)
						.dataClassFields(dialogDataFields)
						.calleeParamMappings(paramMappings)
						.calleeResultMappings(resultMappings)
						.signature(dlgCallSigature)
						.toCreationParams();
		IUserDialogManager.instance().getProjectDataModelFor(project).createProjectUserDialog(params,
				new NullProgressMonitor());
	}

	private static void createFormElement(StringBuffer sb, ExpressFormElement formElement, String datafield) {
		sb.append("<p:outputLabel value='" + formElement.getLabel() + "'/>\n");

		switch (formElement.getElementType()) {
		case "InputFieldText":
			sb.append("<p:inputText value='#{data.data." + datafield + "}'/>\n");
			break;
		case "InputTextArea":
			sb.append("<p:inputTextarea value='#{data.data." + datafield + "}'/>\n");
			break;
		case "ManyCheckbox":
			sb.append("<p:selectManyCheckbox value='#{data.data." + datafield + "}' layout='grid' columns='1'>\n");
			List<String> opts = formElement.getOptionStrs();
			for (String option : opts) {
				sb.append("<f:selectItem itemLabel='" + option + "' itemValue='" + option + "' />\n");
			}
			sb.append("</p:selectManyCheckbox>");
			break;
		case "OneRadio":
			sb.append("<p:selectOneRadio value='#{data.data." + datafield + "}' layout='grid' columns='1'>\n");
			List<String> options = formElement.getOptionStrs();
			for (String option : options) {
				sb.append("<f:selectItem itemLabel='" + option + "' itemValue='" + option + "' />\n");
			}
			sb.append("</p:selectOneRadio>");
		}

	}

	private static void drawElements(List<ExpressTaskDefinition> tasks, Diagram execDiagram, String processname,
			String dataclassName, IProjectProcessManager manager) {
		int x = 96;
		int y = 0;

		y += 128;
		DiagramShape start = execDiagram.add().shape(RequestStart.class).at(x, y);
		start.getLabel().setText("start" + processname + ".ivp");
		RequestStart starter = start.getElement();
		makeExecutable(starter, processname, getSteps(tasks));

		DiagramShape previous = start;
		boolean isfirstTask = true;
		for (ExpressTaskDefinition taskdef : tasks) {
			x += GRID_X;

			if (taskdef.getResponsibles().size() > 1) {
				DiagramShape split = execDiagram.add().shape(TaskSwitchGateway.class).at(x, y);
				split.getLabel().setText("split");
				previous.edges().connectTo(split); // connect
				x += GRID_X;

				DiagramShape current = execDiagram.add().shape(UserTask.class).at(x, y - GRID_Y / 2);
				createUserTask(taskdef, current, dataclassName, isfirstTask, 0);
				isfirstTask = false;
				split.edges().connectTo(current); // connect

				x += GRID_X;
				DiagramShape join = execDiagram.add().shape(TaskSwitchGateway.class).at(x, y);
				join.getLabel().setText("join");
				current.edges().connectTo(join); // connect

				for (int nb = 1; nb < taskdef.getResponsibles().size(); nb++) {
					DiagramShape more = execDiagram.add().shape(UserTask.class).at(x - GRID_X,
							y + nb * GRID_Y - GRID_Y / 2);
					createUserTask(taskdef, more, dataclassName, isfirstTask, nb);
					isfirstTask = false;

					split.edges().connectTo(more); // connect
					// DiagramEdge connectTo = split.edges().connectTo(more);
					// SequenceFlow flow = connectTo.getConnector();
					// bendConnectorLegacy(execDiagram, flow);

					more.edges().connectTo(join); // connect
					// DiagramEdge connectTo2 = more.edges().connectTo(join);
					// SequenceFlow flow2 = connectTo2.getConnector();// connect
					// flow2.setWaypoints(flow2.getWaypoints().add(new Position(x, y + nb
					// * GRID_Y)));
					// bendConnectorLegacy(execDiagram, flow2);
				}
				createSystemTaskGateway(manager, split);

				previous = join;

			} else {
				DiagramShape current = execDiagram.add().shape(UserTask.class).at(x, y);
				createUserTask(taskdef, current, dataclassName, isfirstTask, 0);
				isfirstTask = false;
				previous.edges().connectTo(current); // connect
				// Ivy.log().debug("previuos" + previous);
				if (previous.representsInstanceOf(TaskSwitchGateway.class)) {
					createSystemTaskGateway(manager, previous);
				}

				previous = current;
			}
		}

		x += GRID_X;
		DiagramShape finalreviewtask = execDiagram.add().shape(UserTask.class).at(x, y);
		createFinalReviewTask(finalreviewtask, processname);
		previous.edges().connectTo(finalreviewtask);
		if (previous.representsInstanceOf(TaskSwitchGateway.class)) {
			createSystemTaskGateway(manager, previous);
		}

		x += GRID_X;
		DiagramShape end = execDiagram.add().shape(TaskEnd.class).at(x, y);
		finalreviewtask.edges().connectTo(end);
	}

	// private static void bendConnectorLegacy(Diagram execDiagram, SequenceFlow
	// flow) {
	// ProcessZ legacy = (ProcessZ)execDiagram.getProcess().getRootProcess();
	// ZFacade zFacade = legacy.internal().getZFacade();
	// new
	// DefaultArcComputer(zFacade).breach(flow.getLegacyAPI().getZObject().getField(),
	// false).execute();
	// }

	private static void createSystemTaskGateway(IProjectProcessManager manager, DiagramShape taskGateway) {
		manager.getProcessConfigurator().updateElement(taskGateway.getElement().getLegacyAPI().getZObject());
		TaskSwitchGateway gateway = taskGateway.getElement();
		// gateway.setTaskConfigs(task -> task.setTaskConfig(null, null));
		TaskConfigs taskConfigs = gateway.getTaskConfigs();
		Set<TaskIdentifier> taskIdentifiers = taskConfigs.getTaskIdentifiers();
		for (TaskIdentifier ident : taskIdentifiers) {
			// Ivy.log().debug("task:" + ident);
			TaskConfig taskConfig = taskConfigs.getTaskConfig(ident);
			taskConfig = taskConfig.setName("SYSTEM " + taskGateway.getLabel());
			taskConfig = taskConfig.setActivator(new Activator("SYSTEM", ActivatorType.ROLE));
			taskConfigs = taskConfigs.setTaskConfig(ident, taskConfig);
		}
		gateway.setTaskConfigs(taskConfigs);

		// Ivy.log().info("read: " + gateway.getTaskConfigs());
	}

	private static String getSteps(List<ExpressTaskDefinition> tasks) {
		StringBuffer sb = new StringBuffer();
		sb.append("\"");
		for (ExpressTaskDefinition task : tasks) {
			sb.append(task.getSubject());
			sb.append(",");
		}
		sb.append("Final Review");
		sb.append("\"");
		return sb.toString();
	}

	private static void createUserTask(ExpressTaskDefinition taskdef, DiagramShape current, String dataclassName,
			boolean isfirstTask, int index) {

		current.getLabel().setText(taskdef.getSubject());

		UserTask usertask = current.getElement();
		usertask.setName(taskdef.getSubject());

		TaskConfig taskConfig = usertask.getTaskConfig();
		taskConfig = taskConfig.setName(taskdef.getSubject());
		taskConfig = taskConfig.setDescription(taskdef.getDescription() == null ? "" : taskdef.getDescription());

		taskConfig = taskConfig.setTaskListSkipped(isfirstTask);

		Activator activator = new Activator("\"" + taskdef.getResponsibles().get(index) + "\"",
				ActivatorType.ROLE_FROM_ATTRIBUTE);
		taskConfig = taskConfig.setActivator(activator);

		List<CustomField> customFields = taskConfig.getCustomFields();
		customFields.add(new CustomField("dynaform",
				new IvyScriptExpression(
						"\"" + BusinessEntityConverter.entityToJsonValue(taskdef).replace('"', '\'') + "\""),
				CustomField.Type.STRING));
		customFields.add(new CustomField("stepindex", new IvyScriptExpression("" + (taskdef.getTaskPosition() - 1)),
				CustomField.Type.NUMBER));
		taskConfig = taskConfig.setCustomFields(customFields);

		taskConfig = taskConfig.setExpiryDelay("new Duration(0,0," + taskdef.getUntilDays() + ",0,0,0)");

		usertask.setTaskConfig(taskConfig);

		/*
		 * Generic Dyna form dialog List<VariableDesc> inputParameters =
		 * Arrays.asList(new
		 * VariableDesc("execdata","gawfs.ExecutePredefinedWorkflowData"));
		 * List<VariableDesc> outputParameters = Arrays .asList(new
		 * VariableDesc("execdata","gawfs.ExecutePredefinedWorkflowData"));
		 * CallSignature callSigature = new CallSignature("start", inputParameters,
		 * outputParameters); UserDialogStart userDialogStart =
		 * usertask.getTargetDialog().setId(UserDialogId.create(
		 * "ch.ivy.gawfs.workflowExecution.ExportedUserTaskForm"))
		 * .setStartMethod(callSigature); usertask.setTargetDialog(userDialogStart);
		 * usertask.setParameters(MappingCode.mapOnly("param.execdata",
		 * "in.executePredefinedWorkflowData")); usertask.setOutput(MappingCode.mapOnly(
		 * "out.executePredefinedWorkflowData", "result.execdata"));
		 */

		List<VariableDesc> inputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		List<VariableDesc> outputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		CallSignature callSigature = new CallSignature("start", inputParameters, outputParameters);
		UserDialogStart userDialogStart = usertask.getTargetDialog()
				.setId(UserDialogId.create(NAMESPACE + StringUtil.toJavaIdentifier(taskdef.getSubject())))
				.setStartMethod(callSigature);
		usertask.setTargetDialog(userDialogStart);
		usertask.setParameters(MappingCode.mapOnly("param.data", "in"));
		usertask.setOutput(MappingCode.mapOnly("out", "result.data"));
	}

	private static void createFinalReviewTask(DiagramShape finalreviewtask, String processname) {
		finalreviewtask.getLabel().setText("Final Review");

		UserTask usertask = finalreviewtask.getElement();
		usertask.setName("Final Review");
		usertask.setDescription("Exported AxonIvyExpress Workflow " + processname);

		TaskConfig taskConfig = usertask.getTaskConfig();
		taskConfig = taskConfig.setName(processname + ": Final Review");
		taskConfig.setDescription("The workflow " + processname + " has been finsihed");

		taskConfig = taskConfig.setActivator(new Activator("CREATOR", ActivatorType.ROLE));
		usertask.setTaskConfig(taskConfig);

		List<VariableDesc> inputParameters = Arrays
				.asList(new VariableDesc("execdata", "gawfs.ExecutePredefinedWorkflowData"));
		List<VariableDesc> outputParameters = Arrays.asList();
		CallSignature callSignature = new CallSignature("startExported", inputParameters, outputParameters);
		UserDialogStart userDialogStart = usertask.getTargetDialog()
				.setId(UserDialogId.create("ch.ivy.gawfs.workflowExecution.FinalReviewForm"))
				.setStartMethod(callSignature);
		usertask.setTargetDialog(userDialogStart);
		usertask.setParameters(MappingCode.mapOnly("param.execdata", "in.executePredefinedWorkflowData"));
	}

	private static void makeExecutable(RequestStart starter, String processname, String steps) {
		starter.setLinkName("start_" + processname + ".ivp");
		starter.setDescription(processname);
		starter.setStartByHttpRequestAllowed(true);
		starter.setStartName(processname);
		StartAccessPermissions permissions = new StartAccessPermissions("Everybody");
		starter.setRequiredPermissions(permissions);

		CaseConfig caseConfig = starter.getCaseConfig();
		caseConfig = caseConfig.setName(processname);
		List<CustomField> customFields = caseConfig.getCustomFields();
		customFields.add(new CustomField("steps", new IvyScriptExpression(steps), CustomField.Type.STRING));
		customFields
				.add(new CustomField("embedInFrame", new IvyScriptExpression("\"False\""), CustomField.Type.STRING));
		caseConfig = caseConfig.setCustomFields(customFields);
		starter.setCaseConfig(caseConfig);
	}

	private static void refreshTree(IProject project) {
		try {
			if (Advisor.instance().isDesigner()) {
				project.getProject().build(IResource.PROJECT, new NullProgressMonitor());
				project.getFolder("processes").refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
			}
		} catch (CoreException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws IOException, ResourceDataModelException {
		Path path = Paths.get("C:/XIVY/CodeCamp21-ExpressExporter/AXONIVY_DatasetExport_ExpressWorkflow_30.09.2021 15_24.json");
		String str = Files.readAllLines(path).get(0);

		importJson(str);
	}
}
