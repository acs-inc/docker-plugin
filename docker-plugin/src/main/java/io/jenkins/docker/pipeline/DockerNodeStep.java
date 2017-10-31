package io.jenkins.docker.pipeline;

import com.google.common.collect.ImmutableSet;
import com.nirima.jenkins.plugins.docker.DockerCloud;
import com.nirima.jenkins.plugins.docker.DockerSlave;
import com.nirima.jenkins.plugins.docker.DockerTemplate;
import com.nirima.jenkins.plugins.docker.DockerTemplateBase;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import io.jenkins.docker.connector.DockerComputerAttachConnector;
import io.jenkins.docker.connector.DockerComputerConnector;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerNodeStep extends Step {

    private String cloudName;

    private DockerTemplateBase template;

    private String remoteFs;

    private DockerComputerConnector connector;

    @DataBoundConstructor
    public DockerNodeStep(String cloudName, DockerTemplateBase template, String remoteFs) {
        this.cloudName = cloudName;
        this.template = template;
        this.remoteFs = remoteFs;
        this.connector = new DockerComputerAttachConnector();
    }

    public String getCloudName() {
        return cloudName;
    }

    public DockerTemplateBase getTemplate() {
        return template;
    }

    public String getRemoteFs() {
        return remoteFs;
    }

    public DockerComputerConnector getConnector() {
        return connector;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, cloudName, template, remoteFs, connector);
    }


    private static class Execution extends StepExecution {


        private final DockerTemplateBase template;
        private final String cloudName;
        private final String remoteFs;
        private final DockerComputerConnector connector;

        public Execution(StepContext context, String cloudName, DockerTemplateBase template, String remoteFs, DockerComputerConnector connector) {
            super(context);
            this.cloudName = cloudName;
            this.template = template;
            this.remoteFs = remoteFs;
            this.connector = connector;
        }

        @Override
        public boolean start() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            final DockerCloud cloud = DockerCloud.getCloudByName(cloudName);

            final String uuid = UUID.randomUUID().toString();

            final DockerTemplate t = new DockerTemplate(template, uuid, remoteFs, null, "1", Collections.EMPTY_LIST);
            t.setConnector(connector);
            t.setMode(Node.Mode.EXCLUSIVE); // Doing this we enforce no other task will use this agent

            // TODO launch asynchronously, not in CPS VM thread
            final DockerSlave slave = cloud.provisionFromTemplate(t, listener);
            Jenkins.getInstance().addNode(slave);

            // FIXME we need to wait for node to be online ...
            while (slave.toComputer().isOffline()) {
                Thread.sleep(1000);
            }

            final FilePath ws = slave.createPath(remoteFs);
            FlowNode flowNode = getContext().get(FlowNode.class);
            flowNode.addAction(new WorkspaceActionImpl(ws, flowNode));
            getContext().newBodyInvoker().withContexts(slave.toComputer(), ws).start();

            return false;
        }

        @Override
        public void stop(@Nonnull Throwable throwable) throws Exception {
            final DockerSlave slave = getContext().get(DockerSlave.class);
            if (slave != null) slave.terminate();
        }
    }

    @Extension(optional = true)
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "dockerNode";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Docker Node";
        }


        @Override public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(TaskListener.class, FlowNode.class);
        }

        @Override public Set<? extends Class<?>> getProvidedContext() {
            // TODO can/should we provide Executor? We cannot access Executor.start(WorkUnit) from outside the package. cf. isAcceptingTasks, withContexts
            return ImmutableSet.of(Computer.class, FilePath.class, /* DefaultStepContext infers from Computer: */ Node.class, Launcher.class);
        }

        public ListBoxModel doFillCloudNameItems() {
            ListBoxModel model = new ListBoxModel();
            for (Cloud cloud : DockerCloud.instances()) {
                model.add(cloud.name);
            }
            return model;
        }
    }

}
