// Copyright (c) Keith D Gregory
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.kdgregory.log4j2.aws;

import java.util.Date;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;

import com.kdgregory.log4j2.aws.internal.AbstractAppender;
import com.kdgregory.log4j2.aws.internal.AbstractAppenderBuilder;
import com.kdgregory.log4j2.aws.internal.CloudWatchAppenderConfig;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchConstants;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterConfig;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterFactory;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterStatistics;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterStatisticsMXBean;
import com.kdgregory.logging.aws.common.Substitutions;
import com.kdgregory.logging.common.factories.DefaultThreadFactory;
import com.kdgregory.logging.common.util.InternalLogger;
import com.kdgregory.logging.common.util.RotationMode;


/**
 *  An appender that writes to a CloudWatch log stream.
 *  <p>
 *  This appender supports the following configuration parameters:
 *  <p>
 *  <table>
 *
 *  <tr VALIGN="top">
 *      <th> logGroup
 *      <td> Name of the CloudWatch log group where messages are sent; may use
 *           substitutions. If this group doesn't exist it will be created.
 *           <p>
 *           You typically assign a single log group to an application, and then
 *           use multiple log streams for instances of that application.
 *           <p>
 *           There is no default value. If you do not configure the log group,
 *           the appender will be disabled and will report its misconfiguration.
 *
 *  <tr VALIGN="top">
 *      <th> logStream
 *      <td> Name of the CloudWatch log stream where messages are sent; may use
 *           substitutions. If this stream doesn't exist it will be created.
 *           <p>
 *           You typically create a separate log stream for each instance of
 *           the application.
 *           <p>
 *           Default value is <code>{startTimestamp}</code>, the JVM startup
 *           timestamp.
 *
 *  <tr VALIGN="top">
 *      <th> retentionPeriod
 *      <td> (optional) Specifies a retention period for created CloudWatch log
 *           groups. If omitted, messages are retained forever. Note that values
 *           are restricted; see {@link <a href="https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutRetentionPolicy.html#CWL-PutRetentionPolicy-request-retentionInDays">AWS API doc</a>}.
 *
 *  <tr VALIGN="top">
 *      <th> dedicatedWriter
 *      <td> If true, the appender assumes that it will be the only writer to
 *           the log stream, and will not retrieve a sequence token before each
 *           write. Defaults to false for legacy behavior.
 *
 *  <tr VALIGN="top">
 *      <th> rotationMode
 *      <td> Controls whether auto-rotation is enabled. Values are none, count,
 *           interval, hourly, and daily; default is none.
 *
 *  <tr VALIGN="top">
 *      <th> rotationInterval
 *      <td> Sets the rotation interval, for those appenders that support rotation.
 *           This parameter is valid only when the <code>rotationMode</code> parameter
 *           is "interval" or "count": for the former, it's the number of milliseconds
 *           between rotations, for the latter the number of messages.
 *           <p>
 *           If using interval rotation, you should include <code>{timestamp}</code>
 *           in the log stream name. If using counted rotation, you should include
 *           <code>{sequence}</code>.
 *
 *  <tr VALIGN="top">
 *      <th> sequence
 *      <td> A value that is incremented each time the stream is rotated. May be
 *           accessed as the <code>{sequence}</code> substitution. Defaults to 0.
 *
 *  <tr VALIGN="top">
 *      <th> synchronous
 *      <td> If true, the appender will operate in synchronous mode: calls to
 *           append() execute on the caller's thread, and will not return until
 *           writer has attempted to send a batch (errors may still result in
 *           messages being requeued for the next batch). This negates the benefits
 *           of message batching, and should only be used in cases (like AWS Lambda)
 *           where background thread processing is not guaranteed.
 *          <p>
 *          Note: setting <code>synchronous</code> to true also sets
 *          <code>batchDelay</code> to 0.
 *
 *  <tr VALIGN="top">
 *      <th> batchDelay
 *      <td> The time, in milliseconds, that the writer will wait to accumulate
 *           messages for a batch.
 *           <p>
 *           The writer attempts to gather multiple logging messages into a batch,
 *           to reduce communication with the service. The batch delay controls
 *           the time that a message will remain in-memory while the writer builds
 *           this batch. In a low-volume environment it will be the main determinant
 *           of when the batch is sent; in a high volume environment it's likely
 *           that the maximum request size will be reached before the delay elapses.
 *           <p>
 *           The default value is 2000, which is rather arbitrarily chosen.
 *           <p>
 *           If the appender is in synchronous mode, this setting is ignored.
 *
 *  <tr VALIGN="top">
 *      <th> discardThreshold
 *      <td> The number of unsent messages that will trigger message discard. A
 *           high value is useful when network connectivity is intermittent and/or
 *           overall AWS communication is causing throttling. However, a value that
 *           is too high may cause out-of-memory errors.
 *           <p>
 *           The default, 10,000, is based on the assumptions that (1) each message
 *           will be 1k or less, and (2) any app that uses remote logging can afford
 *           10MB.
 *           <p>
 *
 *  <tr VALIGN="top">
 *      <th> discardAction
 *      <td> The action to take when the number of unsent messages exceeds the
 *           discard threshold. Values are "none" (retain all messages), "oldest"
 *           (discard oldest messages), and "newest" (discard most recent messages).
 *           <p>
 *           The default is "oldest". Attempting to set an incorrect value will throw
 *           a configuration error.
 *
 *  <tr VALIGN="top">
 *      <th> clientFactory
 *      <td> The fully-qualified name of a static method to create the correct AWS
 *           client, which will be called instead of the writer's internal client
 *           factory. This is useful if you need non-default configuration, such as
 *           using a proxy server.
 *           <p>
 *           The passed string is of the form <code>com.example.Classname.methodName</code>.
 *           If this does not reference a class/method on the classpath then writer
 *           initialization will fail.
 *
 *  <tr VALIGN="top">
 *      <th> clientRegion
 *      <td> Specifies a non-default service region. This setting is ignored if you
 *           use a client factory.
 *           <p>
 *           Note that the region must be supported by the current SDK version.
 *
 *  <tr VALIGN="top">
 *      <th> clientEndpoint
 *      <td> Specifies a non-default service endpoint. This is intended for use with
 *           older AWS SDK versions that do not provide client factories and default
 *           to us-east-1 for constructed clients, although it can be used for newer
 *           releases when you want to override the default region provider. This
 *           setting is ignored if you use a client factory.
 *
 *  <tr VALIGN="top">
 *      <th> useShutdownHook
 *      <td> Controls whether the appender uses a shutdown hook to attempt to process
 *           outstanding messages when the JVM exits. This is true by default; set to
 *           false to disable.
 *  </table>
 *
 *  @see <a href="https://github.com/kdgregory/log4j-aws-appenders/blob/master/docs/cloudwatch.md">Appender documentation</a>
 */
@Plugin(name = "CloudWatchAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class CloudWatchAppender
extends AbstractAppender<CloudWatchAppenderConfig,CloudWatchWriterStatistics,CloudWatchWriterStatisticsMXBean,CloudWatchWriterConfig>
{

//----------------------------------------------------------------------------
//  Builder
//----------------------------------------------------------------------------

    @PluginBuilderFactory
    public static CloudWatchAppenderBuilder newBuilder() {
        return new CloudWatchAppenderBuilder();
    }

    public static class CloudWatchAppenderBuilder
    extends AbstractAppenderBuilder<CloudWatchAppenderBuilder>
    implements CloudWatchAppenderConfig, org.apache.logging.log4j.core.util.Builder<CloudWatchAppender>
    {
        @PluginBuilderAttribute("name")
        @Required(message = "CloudWatchAppender: no name provided")
        private String name;

        @Override
        public String getName()
        {
            return name;
        }

        public CloudWatchAppenderBuilder setName(String value)
        {
            this.name = value;
            return this;
        }


        @PluginBuilderAttribute("logGroup")
        private String logGroup;

        /**
         *  Sets the <code>logGroup</code> configuration property.
         */
        public CloudWatchAppenderBuilder setLogGroup(String value)
        {
            this.logGroup = value;
            return this;
        }

        /**
         *  Returns the <code>logGroup</code> configuration property.
         */
        @Override
        public String getLogGroup()
        {
            return logGroup;
        }


        @PluginBuilderAttribute("logStream")
        private String logStream = "{startupTimestamp}";

        /**
         *  Sets the <code>logStream</code> configuration property.
         */
        public CloudWatchAppenderBuilder setLogStream(String value)
        {
            this.logStream = value;
            return this;
        }

        /**
         *  Returns the <code>logStream</code> configuration property.
         */
        @Override
        public String getLogStream()
        {
            return logStream;
        }


        @PluginBuilderAttribute("retentionPeriod")
        private Integer retentionPeriod;

        /**
         *  Sets the <code>retentionPeriod</code> configuration property.
         */
        public CloudWatchAppenderBuilder setRetentionPeriod(Integer value)
        {
            // note: validation happens in appender because Log4J bypasses
            //       this setter during normal configuration
            this.retentionPeriod = value;
            return this;
        }

        /**
         *  Returns the <code>retentionPeriod</code> configuration property,
         *  null to indicate unlimited retention.
         */
        @Override
        public Integer getRetentionPeriod()
        {
            return retentionPeriod;
        }


        @PluginBuilderAttribute("dedicatedWriter")
        private boolean dedicatedWriter;

        /**
         *  Sets the <code>dedicatedWriter</code> configuration property.
         */
        public CloudWatchAppenderBuilder setDedicatedWriter(boolean value)
        {
            this.dedicatedWriter = value;
            return this;
        }

        /**
         *  Returns the <code>dedicatedWriter</code> configuration property.
         */
        @Override
        public boolean isDedicatedWriter()
        {
            return dedicatedWriter;
        }


        @PluginBuilderAttribute("rotationMode")
        private String rotationMode = RotationMode.none.name();

        /**
         *  Sets the <code>rotationMode</code> configuration property.
         */
        public CloudWatchAppenderBuilder setRotationMode(String value)
        {
            // note: validation happens in appender because Log4J bypasses
            //       this setter during normal configuration
            this.rotationMode = value;
            return this;
        }

        /**
         *  Returns the <code>rotationMode</code> configuration property.
         */
        @Override
        public String getRotationMode()
        {
            return rotationMode;
        }


        @PluginBuilderAttribute("rotationInterval")
        private long rotationInterval = -1;

        /**
         *  Sets the <code>rotationInterval</code> configuration property.
         */
        public CloudWatchAppenderBuilder setRotationInterval(long value)
        {
            this.rotationInterval = value;
            return this;
        }

        /**
         *  Returns the <code>rotationInterval</code> configuration property.
         */
        @Override
        public long getRotationInterval()
        {
            return rotationInterval;
        }


        @PluginBuilderAttribute("sequence")
        private int sequence;

        /**
         *  Sets the <code>rotationInterval</code> configuration property.
         */
        public CloudWatchAppenderBuilder setSequence(int value)
        {
            this.sequence = value;
            return this;
        }


        @PluginBuilderAttribute("accessKey")
        private String accessKey;

        /**
         *  Sets the <code>rotationInterval</code> configuration property.
         */
        public CloudWatchAppenderBuilder setAccessKey(String accessKey)
        {
            this.accessKey = accessKey;
            return this;
        }

        @Override
        public String getAccessKey() {
            return accessKey;
        }

        @PluginBuilderAttribute("secretKey")
        private String secretKey;

        /**
         *  Sets the <code>rotationInterval</code> configuration property.
         */
        public CloudWatchAppenderBuilder setSecretKey(String secretKey)
        {
            this.secretKey = secretKey;
            return this;
        }

        @Override
        public String getSecretKey() {
            return secretKey;
        }

        /**
         *  Returns the <code>rotationInterval</code> configuration property.
         */
        @Override
        public int getSequence()
        {
            return sequence;
        }


        @Override
        public CloudWatchAppender build()
        {
            return new CloudWatchAppender(name, this, null);
        }
    }

//----------------------------------------------------------------------------
//  Appender
//----------------------------------------------------------------------------

    // this is extracted from config so that we can validate

    protected Integer retentionPeriod;

    protected CloudWatchAppender(String name, CloudWatchAppenderConfig config, InternalLogger internalLogger)
    {
        super(
            name,
            new DefaultThreadFactory("log4j2-cloudwatch"),
            new CloudWatchWriterFactory(),
            new CloudWatchWriterStatistics(),
            CloudWatchWriterStatisticsMXBean.class,
            config,
            internalLogger);

        try
        {
            retentionPeriod = CloudWatchConstants.validateRetentionPeriod(config.getRetentionPeriod());
        }
        catch (IllegalArgumentException ex)
        {
            internalLogger.error(ex.getMessage(), null);
        }
    }

//----------------------------------------------------------------------------
//  Additional public API
//----------------------------------------------------------------------------

    /**
     *  Explicitly switch to a new log stream.
     */
    @Override
    protected void rotate()
    {
        super.rotate();
    }

//----------------------------------------------------------------------------
//  Internals
//----------------------------------------------------------------------------

    @Override
    protected CloudWatchWriterConfig generateWriterConfig()
    {
        StrSubstitutor l4jsubs = config.getConfiguration().getStrSubstitutor();
        Substitutions subs     = new Substitutions(new Date(), sequence.get());
        String actualLogGroup  = subs.perform(l4jsubs.replace(config.getLogGroup()));
        String actualLogStream = subs.perform(l4jsubs.replace(config.getLogStream()));

        return new CloudWatchWriterConfig(
            actualLogGroup, actualLogStream, retentionPeriod,
            config.isDedicatedWriter(), config.getBatchDelay(),
            config.getDiscardThreshold(), discardAction,
            config.getClientFactory(), config.getClientRegion(), config.getClientEndpoint(), config.getAccessKey(), config.getSecretKey());
    }
}
