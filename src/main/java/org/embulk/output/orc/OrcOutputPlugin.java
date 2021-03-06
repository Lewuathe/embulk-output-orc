package org.embulk.output.orc;

import com.amazonaws.auth.AWSCredentials;
import com.google.common.base.Throwables;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.util.VersionInfo;
import org.apache.orc.CompressionKind;
import org.apache.orc.MemoryManager;
import org.apache.orc.OrcFile;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.Exec;
import org.embulk.spi.OutputPlugin;
import org.embulk.spi.PageReader;
import org.embulk.spi.Schema;
import org.embulk.spi.TransactionalPageOutput;
import org.embulk.spi.time.TimestampFormatter;
import org.embulk.spi.type.Type;
import org.embulk.spi.util.Timestamps;
import org.embulk.util.aws.credentials.AwsCredentials;

import java.io.IOException;
import java.util.List;

public class OrcOutputPlugin
        implements OutputPlugin
{
    @Override
    public ConfigDiff transaction(ConfigSource config,
            Schema schema, int taskCount,
            OutputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        // retryable (idempotent) output:
        // return resume(task.dump(), schema, taskCount, control);

        // non-retryable (non-idempotent) output:
        control.run(task.dump());
        return Exec.newConfigDiff();
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
            Schema schema, int taskCount,
            OutputPlugin.Control control)
    {
        throw new UnsupportedOperationException("orc output plugin does not support resuming");
    }

    @Override
    public void cleanup(TaskSource taskSource,
            Schema schema, int taskCount,
            List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TransactionalPageOutput open(TaskSource taskSource, Schema schema, int taskIndex)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        if (task.getOverwrite()) {
            AWSCredentials credentials = AwsCredentials.getAWSCredentialsProvider(task).getCredentials();
            OrcOutputPluginHelper.removeOldFile(buildPath(task, taskIndex), task);
        }

        final PageReader reader = new PageReader(schema);
        Writer writer = createWriter(task, schema, taskIndex);

        return new OrcTransactionalPageOutput(reader, writer, task);
    }

    private String buildPath(PluginTask task, int processorIndex)
    {
        final String pathPrefix = task.getPathPrefix();
        final String pathSuffix = task.getFileNameExtension();
        final String sequenceFormat = task.getSequenceFormat();
        return pathPrefix + String.format(sequenceFormat, processorIndex) + pathSuffix;
    }

    static TypeDescription getSchema(Schema schema)
    {
        TypeDescription oschema = TypeDescription.createStruct();
        for (int i = 0; i < schema.size(); i++) {
            Column column = schema.getColumn(i);
            Type type = column.getType();
            switch (type.getName()) {
                case "long":
                    oschema.addField(column.getName(), TypeDescription.createLong());
                    break;
                case "double":
                    oschema.addField(column.getName(), TypeDescription.createDouble());
                    break;
                case "boolean":
                    oschema.addField(column.getName(), TypeDescription.createBoolean());
                    break;
                case "string":
                    oschema.addField(column.getName(), TypeDescription.createString());
                    break;
                case "timestamp":
                    oschema.addField(column.getName(), TypeDescription.createTimestamp());
                    break;
                default:
                    System.out.println("Unsupported type");
                    break;
            }
        }
        return oschema;
    }

    private Configuration getHadoopConfiguration(PluginTask task)
    {
        Configuration conf = new Configuration();

        // see: https://stackoverflow.com/questions/17265002/hadoop-no-filesystem-for-scheme-file
        conf.set("fs.hdfs.impl", DistributedFileSystem.class.getName());
        conf.set("fs.file.impl", LocalFileSystem.class.getName());
        // see: https://stackoverflow.com/questions/20833444/how-to-set-objects-in-hadoop-configuration

        AwsCredentials.getAWSCredentialsProvider(task);
        if (task.getAccessKeyId().isPresent()) {
            conf.set("fs.s3a.access.key", task.getAccessKeyId().get());
            conf.set("fs.s3n.awsAccessKeyId", task.getAccessKeyId().get());
        }
        if (task.getSecretAccessKey().isPresent()) {
            conf.set("fs.s3a.secret.key", task.getSecretAccessKey().get());
            conf.set("fs.s3n.awsSecretAccessKey", task.getSecretAccessKey().get());
        }
        if (task.getEndpoint().isPresent()) {
            conf.set("fs.s3a.endpoint", task.getEndpoint().get());
            conf.set("fs.s3n.endpoint", task.getEndpoint().get());
        }
        return conf;
    }

    private Writer createWriter(PluginTask task, Schema schema, int processorIndex)
    {
        final TimestampFormatter[] timestampFormatters = Timestamps
                .newTimestampColumnFormatters(task, schema, task.getColumnOptions());

        Configuration conf = getHadoopConfiguration(task);
        TypeDescription oschema = getSchema(schema);

        // see: https://groups.google.com/forum/#!topic/vertx/lLb-slzpWVg
        Thread.currentThread().setContextClassLoader(VersionInfo.class.getClassLoader());

        Writer writer = null;
        try {
            // Make writerOptions
            OrcFile.WriterOptions writerOptions = createWriterOptions(task, conf);
            // see: https://stackoverflow.com/questions/9256733/how-to-connect-hive-in-ireport
            // see: https://community.hortonworks.com/content/kbentry/73458/connecting-dbvisualizer-and-datagrip-to-hive-with.html
            writer = OrcFile.createWriter(
                    new Path(buildPath(task, processorIndex)),
                    writerOptions.setSchema(oschema)
                            .memory(new WriterLocalMemoryManager())
                            .version(OrcFile.Version.V_0_12)
            );
        }
        catch (IOException e) {
            Throwables.throwIfUnchecked(e);
        }
        return writer;
    }

    private OrcFile.WriterOptions createWriterOptions(PluginTask task, Configuration conf)
    {
        final Integer bufferSize = task.getBufferSize();
        final Integer stripSize = task.getStripSize();
        final Integer blockSize = task.getBlockSize();
        final String kindString = task.getCompressionKind();
        CompressionKind kind = CompressionKind.valueOf(kindString);
        return OrcFile.writerOptions(conf)
                .bufferSize(bufferSize)
                .blockSize(blockSize)
                .stripeSize(stripSize)
                .compress(kind);
    }

    // We avoid using orc.MemoryManagerImpl since it is not threadsafe, but embulk is multi-threaded.
    // Embulk creates and uses multiple instances of TransactionalPageOutput in worker threads.
    // As a workaround, WriterLocalMemoryManager is bound to a single orc.Writer instance, and
    // notifies checkMemory() only to that instance.
    private static class WriterLocalMemoryManager
            implements MemoryManager
    {
        final long rowsBetweenChecks = 10000;

        private int rowsAddedSinceCheck = 0;
        Callback boundCallback = null;

        @Override
        public void addWriter(Path path, long requestedAllocation, Callback callback)
                throws IOException
        {
            if (boundCallback != null) {
                throw new IllegalStateException("WriterLocalMemoryManager should be bound to a single orc.Writer instance.");
            }

            boundCallback = callback;
        }

        @Override
        public void removeWriter(Path path)
                throws IOException
        {
            boundCallback = null;
        }

        @Override
        public void addedRow(int rows)
                throws IOException
        {
            rowsAddedSinceCheck += rows;
            if (rowsAddedSinceCheck > rowsBetweenChecks) {
                boundCallback.checkMemory(1);
                rowsAddedSinceCheck = 0;
            }
        }
    }
}
