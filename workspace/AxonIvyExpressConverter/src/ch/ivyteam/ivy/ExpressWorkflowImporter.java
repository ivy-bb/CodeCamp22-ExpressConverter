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
import ch.ivyteam.ivy.environment.Ivy;
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
import ch.ivyteam.ivy.process.model.element.value.Mappings;
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
import ch.ivyteam.ivy.server.restricted.EngineMode;
import ch.ivyteam.util.StringUtil;

@SuppressWarnings("restriction")
public class ExpressWorkflowImporter {

	static final int GRID_X = 128;
	static final int GRID_Y = 96;
	static final String NAMESPACE = "axon.ivy.express.export.";

	public static void importJson(String json) throws IOException, ResourceDataModelException {
		Gson gson = new GsonBuilder().serializeNulls().create();
		JsonObject jsonObject = gson.fromJson(json, JsonObject.class);

		JsonElement workflowsElement = jsonObject.get("expressWorkflow");
		List<ExpressProcess> expressProcessEntities = BusinessEntityConverter
				.jsonValueToEntities(workflowsElement.toString(), ExpressProcess.class);
		for (ExpressProcess expressProcess : expressProcessEntities) {
			convert(expressProcess);
		}
	}

	private static void convert(ExpressProcess expressProcess) throws ResourceDataModelException, IOException {
		
		/*
		IProcessModel pm = IApplication.current().createProcessModel("AxonIvyExpressExport");
		IProcessModelVersion pmv = pm.createProcessModelVersion("AxonIvyExpressExport0", "", "", "", 1);
		*/
		
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

		createDialogs(expressProcess.getTaskDefinitions(), project, dataFields, dataclassName, processName);

		ProcessCreator creator = ProcessCreator.create(project, processName).kind(ProcessKind.NORMAL).namespace("")
				.dataClassName(dataclassName).createDefaultContent(false).dataClassFields(dataFields).toCreator();

		creator.createDataModel(new NullProgressMonitor());
		process = creator.getCreatedProcess();

		Diagram diagram = process.getModel().getDiagram();

		drawElements(expressProcess.getTaskDefinitions(), diagram, expressProcess.getProcessName(), dataclassName, dataFields, 
				manager);

		process.save();
		refreshTree(project);
	}

	private static void createDialogs(List<ExpressTaskDefinition> tasks, IProject project,
			List<VariableDesc> dataFields, String dataclassName, String processName)
			throws ResourceDataModelException, IOException {
		for (ExpressTaskDefinition taskdef : tasks) {
			createDialogComponent(taskdef, project, dataFields, dataclassName);
		}
		createDialogMaster(tasks, project, dataFields, dataclassName, processName);
	}

	private static void createDialogMaster(List<ExpressTaskDefinition> tasks, IProject project,
			List<VariableDesc> dataFields, String dataclassName, String processName)
			throws ResourceDataModelException, IOException {
		StringBuffer stepsPanel = new StringBuffer();
		StringBuffer formPanel = new StringBuffer();

		writeDialogMasterStepPanel(tasks, stepsPanel);
		writeDialogMasterFormPanel(tasks, formPanel);

		InputStream is = ExpressWorkflowImporter.class.getResourceAsStream("/resources/dialog_template.xhtml");
		String template = new String(is.readAllBytes());
		template = template.replace("${pagetitle}", processName);
		template = template.replace("${stepspanel}", stepsPanel.toString());
		template = template.replace("${formpanel}", formPanel.toString());
		is.close();

		List<VariableDesc> inputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		List<VariableDesc> outputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		CallSignature dlgCallSigature = new CallSignature("start", inputParameters, outputParameters);

		List<VariableDesc> dlgDataFields = Arrays.asList(new VariableDesc("processData", dataclassName),
				new VariableDesc("currentStep", "java.lang.Integer"),
				new VariableDesc("parallelIndex", "java.lang.Integer"));
		List<Mapping> paramMappings = Arrays.asList(new Mapping("out.processData", "param.data"),
				new Mapping("out.currentStep", "ivy.task.customFields().numberField(\"stepindex\").getOrDefault(0)"),
				new Mapping("out.parallelIndex", "ivy.task.customFields().numberField(\"parallelindex\").getOrDefault(0)"));
		List<Mapping> resultMappings = Arrays.asList(new Mapping("result.data", "in.processData"));

		DialogCreationParameters params = new DialogCreationParameters.Builder(project,
				NAMESPACE + StringUtil.toJavaIdentifier("TaskDialog")).viewContent(template)
						.dataClassFields(dlgDataFields).calleeParamMappings(paramMappings)
						.calleeResultMappings(resultMappings).signature(dlgCallSigature).toCreationParams();
		IUserDialogManager.instance().getProjectDataModelFor(project).createProjectUserDialog(params,
				new NullProgressMonitor());
	}

	private static void writeDialogMasterFormPanel(List<ExpressTaskDefinition> tasks, StringBuffer formPanel) {
		for (int t = 0; t < tasks.size(); t++) {
			ExpressTaskDefinition taskdef = tasks.get(t);
			String componentName = StringUtil.toJavaIdentifier(taskdef.getSubject());
			String componentData = "data.processData." + componentName.toLowerCase();
			
			formPanel.append("<p:panel styleClass='card' rendered=\"#{data.currentStep == " + t 
					+ " and data.parallelIndex == 0}\">\n");
			formPanel.append("<b>Form:</b><ic:" + NAMESPACE + componentName + " data=\"#{" + componentData + "}\" />\n");
			formPanel.append("</p:panel>\n");
			
			for (int parallelInstance = 1; parallelInstance < taskdef.getResponsibles().size(); parallelInstance++) {
				componentData = "data.processData." + componentName.toLowerCase() + (parallelInstance + 1);
				formPanel.append("<p:panel styleClass='card' rendered=\"#{data.currentStep == " + t
						+ " and data.parallelIndex == " + parallelInstance + "}\">\n");
				formPanel.append("<ic:" + NAMESPACE + componentName + " data=\"#{" + componentData + "}\" />\n");
				formPanel.append("</p:panel>\n");
			}
		}
	}

	private static void writeDialogMasterStepPanel(List<ExpressTaskDefinition> tasks, StringBuffer stepsPanel) {
		for (int stepIndex = 0; stepIndex < tasks.size(); stepIndex++) {
			ExpressTaskDefinition taskdef = tasks.get(stepIndex);

			String stepName = taskdef.getSubject();
			String componentName = StringUtil.toJavaIdentifier(taskdef.getSubject());
			String componentData = "data.processData." + componentName.toLowerCase();
			String responsableName = "<b>Applikant:</b> #{" + componentData + ".wfuser}";
			
			stepsPanel.append(
					"<p:fieldset styleClass='finished-fieldset' toggleable='true' rendered='#{data.currentStep gt "
							+ stepIndex + "}' collapsed='true' legend='" + stepName + "'>\n");
			stepsPanel.append("<p:panel styleClass='card'>" + responsableName + "<hr/>\n");
			stepsPanel.append("<b>Form:</b><ic:" + NAMESPACE + componentName + " data=\"#{" + componentData + "}\" />\n");
			stepsPanel.append("</p:panel></p:fieldset>\n");

			for (int parallelInstance = 1; parallelInstance < taskdef.getResponsibles().size(); parallelInstance++) {
				componentData = "data.processData." + componentName.toLowerCase() + (parallelInstance + 1);
				responsableName = "<b>Applikant:</b>#{" + componentData + ".wfuser}";
				stepsPanel.append(
						"<p:fieldset styleClass='finished-fieldset' toggleable='true' rendered='#{data.currentStep gt "
								+ stepIndex + "}' collapsed='true' legend='" + stepName + "'>\n");
				stepsPanel.append("<p:panel styleClass='card'>" + responsableName + "<hr/>\n");
				stepsPanel.append("<b>Form:</b><ic:" + NAMESPACE + componentName + " data=\"#{" + componentData + "}\" />\n");
				stepsPanel.append("</p:panel></p:fieldset>\n");
			}
		}
	}

	private static void createDialogComponent(ExpressTaskDefinition taskdef, IProject project,
			List<VariableDesc> dataFields, String dataclassName) throws ResourceDataModelException, IOException {

		StringBuffer headerPanel = new StringBuffer();
		StringBuffer leftPanel = new StringBuffer();
		StringBuffer rightPanel = new StringBuffer();
		StringBuffer footerPanel = new StringBuffer();

		List<VariableDesc> dialogDataFields = new ArrayList<VariableDesc>();
		dialogDataFields.add(new VariableDesc("wfuser", "java.lang.String"));
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

		String componentName = StringUtil.toJavaIdentifier(taskdef.getSubject());
		String qualifiedComponentName = NAMESPACE + componentName;
		String componenetDataClass = qualifiedComponentName + "." + componentName + "Data";
		dataFields.add(new VariableDesc(componentName.toLowerCase(), componenetDataClass));
		for (int parallelInstance = 1; parallelInstance < taskdef.getResponsibles().size(); parallelInstance++) {
			dataFields.add(new VariableDesc(componentName.toLowerCase()+(parallelInstance+1), componenetDataClass));
		}

		InputStream is = ExpressWorkflowImporter.class.getResourceAsStream("/resources/component_template.xhtml");
		String template = new String(is.readAllBytes());
		template = template.replace("${headerpanelfields}", headerPanel.toString());
		template = template.replace("${leftpanelfields}", leftPanel.toString());
		template = template.replace("${rightpanelfields}", rightPanel.toString());
		template = template.replace("${footerpanelfields}", footerPanel.toString());
		template = template.replace("${tasktitle}", taskdef.getSubject());
		template = template.replace("${taskdescription}", "" + taskdef.getDescription());
		is.close();

		List<VariableDesc> inputParameters = Arrays.asList(new VariableDesc("data", componenetDataClass));
		List<VariableDesc> outputParameters = Arrays.asList(new VariableDesc("data", componenetDataClass));
		CallSignature dlgCallSigature = new CallSignature("start", inputParameters, outputParameters);

		List<Mapping> paramMappings = Arrays.asList(new Mapping("out", "param.data"));
		List<Mapping> resultMappings = Arrays.asList(new Mapping("result.data", "in"),
				new Mapping("result.data.wfuser", "in.wfuser.isEmpty() ? ivy.session.getSessionUserName() : in.wfuser"));

		DialogCreationParameters params = new DialogCreationParameters.Builder(project, qualifiedComponentName)
				.viewContent(template).dataClassFields(dialogDataFields).calleeParamMappings(paramMappings)
				.calleeResultMappings(resultMappings).signature(dlgCallSigature).toCreationParams();
		IUserDialogManager.instance().getProjectDataModelFor(project).createProjectUserDialog(params,
				new NullProgressMonitor());
	}

	private static void createFormElement(StringBuffer sb, ExpressFormElement formElement, String datafield) {
		sb.append("<p:outputLabel value='" + formElement.getLabel() + "'/>\n");

		switch (formElement.getElementType()) {
		case "InputFieldText":
			sb.append("<p:inputText value='#{data." + datafield + "}'/>\n");
			break;
		case "InputTextArea":
			sb.append("<p:inputTextarea value='#{data." + datafield + "}'/>\n");
			break;
		case "ManyCheckbox":
			sb.append("<p:selectManyCheckbox value='#{data." + datafield + "}' layout='grid' columns='1'>\n");
			List<String> opts = formElement.getOptionStrs();
			for (String option : opts) {
				sb.append("<f:selectItem itemLabel='" + option + "' itemValue='" + option + "' />\n");
			}
			sb.append("</p:selectManyCheckbox>");
			break;
		case "OneRadio":
			sb.append("<p:selectOneRadio value='#{data." + datafield + "}' layout='grid' columns='1'>\n");
			List<String> options = formElement.getOptionStrs();
			for (String option : options) {
				sb.append("<f:selectItem itemLabel='" + option + "' itemValue='" + option + "' />\n");
			}
			sb.append("</p:selectOneRadio>");
		}

	}

	private static void drawElements(List<ExpressTaskDefinition> tasks, Diagram execDiagram, String processname,
			String dataclassName, List<VariableDesc> dataFields, IProjectProcessManager manager) {
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
					more.edges().connectTo(join); // connect

				}
				createSystemTaskGateway(manager, dataFields, split);

				previous = join;

			} else {
				DiagramShape current = execDiagram.add().shape(UserTask.class).at(x, y);
				createUserTask(taskdef, current, dataclassName, isfirstTask, 0);
				isfirstTask = false;
				previous.edges().connectTo(current); // connect
				if (previous.representsInstanceOf(TaskSwitchGateway.class)) {
					createSystemTaskGateway(manager, dataFields, previous);
				}

				previous = current;
			}
		}

		x += GRID_X;
		DiagramShape finalreviewtask = execDiagram.add().shape(UserTask.class).at(x, y);
		createFinalReviewTask(finalreviewtask, processname, dataclassName, tasks.size());
		previous.edges().connectTo(finalreviewtask);
		if (previous.representsInstanceOf(TaskSwitchGateway.class)) {
			createSystemTaskGateway(manager, dataFields, previous);
		}

		x += GRID_X;
		DiagramShape end = execDiagram.add().shape(TaskEnd.class).at(x, y);
		finalreviewtask.edges().connectTo(end);
	}

	private static void createSystemTaskGateway(IProjectProcessManager manager, List<VariableDesc> dataFields, DiagramShape taskGateway) {
		manager.getProcessConfigurator().updateElement(taskGateway.getElement().getLegacyAPI().getZObject());
		TaskSwitchGateway gateway = taskGateway.getElement();
		TaskConfigs taskConfigs = gateway.getTaskConfigs();
		Set<TaskIdentifier> taskIdentifiers = taskConfigs.getTaskIdentifiers();
		for (TaskIdentifier ident : taskIdentifiers) {
			TaskConfig taskConfig = taskConfigs.getTaskConfig(ident);
			taskConfig = taskConfig.setName("SYSTEM " + taskGateway.getLabel());
			taskConfig = taskConfig.setActivator(new Activator("SYSTEM", ActivatorType.ROLE));
			taskConfigs = taskConfigs.setTaskConfig(ident, taskConfig);
		}
		gateway.setTaskConfigs(taskConfigs);
		
		if(gateway.getIncoming().size()>1)  // join
		{
			MappingCode mc = gateway.getOutput();
			Mappings ms= mc.getMappings();
			for (VariableDesc dataField: dataFields)
			{	
				String name = dataField.getName();
				String expr = (name.matches(".+[0-9]") ? "in2."+name : "in1."+name);
				ms = ms.add(new Mapping("out."+name, expr));
				mc = mc.setMappings(ms);
			}	
			gateway.setOutput(mc);
		}
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
		customFields.add(new CustomField("stepindex", new IvyScriptExpression("" + (taskdef.getTaskPosition() - 1)),
				CustomField.Type.NUMBER));
		customFields.add(new CustomField("parallelindex", new IvyScriptExpression("" + index),
				CustomField.Type.NUMBER));
		
		taskConfig = taskConfig.setCustomFields(customFields);

		taskConfig = taskConfig.setExpiryDelay("new Duration(0,0," + taskdef.getUntilDays() + ",0,0,0)");

		usertask.setTaskConfig(taskConfig);

		List<VariableDesc> inputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		List<VariableDesc> outputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		CallSignature callSigature = new CallSignature("start", inputParameters, outputParameters);
		UserDialogStart userDialogStart = usertask.getTargetDialog()
				.setId(UserDialogId.create(NAMESPACE + StringUtil.toJavaIdentifier("TaskDialog")))
				.setStartMethod(callSigature);
		usertask.setTargetDialog(userDialogStart);
		usertask.setParameters(MappingCode.mapOnly("param.data", "in"));
		usertask.setOutput(MappingCode.mapOnly("out", "result.data"));
	}

	private static void createFinalReviewTask(DiagramShape finalreviewtask, String processname, String dataclassName,
			int index) {
		finalreviewtask.getLabel().setText("Final Review");

		UserTask usertask = finalreviewtask.getElement();
		usertask.setName("Final Review");
		usertask.setDescription("Exported AxonIvyExpress Workflow " + processname);

		TaskConfig taskConfig = usertask.getTaskConfig();
		taskConfig = taskConfig.setName(processname + ": Final Review");
		taskConfig.setDescription("The workflow " + processname + " has been finsihed");
		taskConfig = taskConfig.setActivator(new Activator("CREATOR", ActivatorType.ROLE));

		List<CustomField> customFields = taskConfig.getCustomFields();
		customFields.add(new CustomField("stepindex", new IvyScriptExpression("" + index), CustomField.Type.NUMBER));
		taskConfig = taskConfig.setCustomFields(customFields);
		usertask.setTaskConfig(taskConfig);

		List<VariableDesc> inputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		List<VariableDesc> outputParameters = Arrays.asList(new VariableDesc("data", dataclassName));
		CallSignature callSigature = new CallSignature("start", inputParameters, outputParameters);
		UserDialogStart userDialogStart = usertask.getTargetDialog()
				.setId(UserDialogId.create(NAMESPACE + StringUtil.toJavaIdentifier("TaskDialog")))
				.setStartMethod(callSigature);
		usertask.setTargetDialog(userDialogStart);
		usertask.setParameters(MappingCode.mapOnly("param.data", "in"));
		usertask.setOutput(MappingCode.mapOnly("out", "result.data"));

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
		customFields.add(new CustomField("embedInFrame", new IvyScriptExpression("\"True\""), CustomField.Type.STRING));
		caseConfig = caseConfig.setCustomFields(customFields);
		starter.setCaseConfig(caseConfig);
	}

	private static void refreshTree(IProject project) {
		try {
			if (EngineMode.isEmbeddedInDesigner()) {
				project.getProject().build(IResource.PROJECT, new NullProgressMonitor());
				project.getFolder("processes").refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
			}
		} catch (CoreException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws IOException, ResourceDataModelException {
		Path path = Paths
				.get("C:/XIVY/CodeCamp21-ExpressExporter/AXONIVY_DatasetExport_ExpressWorkflow_30.09.2021 15_24.json");
		String str = Files.readAllLines(path).get(0);

		importJson(str);
	}
}
