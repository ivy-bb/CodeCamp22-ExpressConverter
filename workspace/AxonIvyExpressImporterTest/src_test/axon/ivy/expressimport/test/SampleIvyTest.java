package axon.ivy.expressimport.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import ch.ivyteam.ivy.ExpressWorkflowImporter;
import ch.ivyteam.ivy.application.IApplication;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.environment.IvyTest;
import ch.ivyteam.ivy.resource.datamodel.ResourceDataModelException;

/**
 * This sample UnitTest runs java code in an environment as it would exists when
 * being executed in Ivy Process. Popular projects API facades, such as {@link Ivy#persistence()}
 * are setup and ready to be used.
 * 
 * <p>The test can either be run<ul>
 * <li>in the Designer IDE ( <code>right click > run as > JUnit Test </code> )</li>
 * <li>or in a Maven continuous integration build pipeline ( <code>mvn clean verify</code> )</li>
 * </ul></p>
 * 
 * <p>Detailed guidance on writing these kind of tests can be found in our
 * <a href="https://developer.axonivy.com/doc/dev/concepts/testing/unit-testing.html">Unit Testing docs</a>
 * </p>
 */
@SuppressWarnings("restriction")
@IvyTest
public class SampleIvyTest{
  
  @Test
  public void useIvy() throws IOException, ResourceDataModelException{
    Ivy.log().info("hi from JUnit");
    assertThat(true).as("I can use Ivy API facade in tests").isEqualTo(true);
	
    Path path = Paths
			.get("C:/XIVY/CodeCamp21-ExpressExporter/AXONIVY_DatasetExport_ExpressWorkflow_30.09.2021 15_24.json");
	String str = Files.readAllLines(path).get(0);
	
	String app1 = Ivy.wf().getApplication().getName();
	String app2 = IApplication.current().getName();
	
	ExpressWorkflowImporter.importJson(str);
  }
  
}
