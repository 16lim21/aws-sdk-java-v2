/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.core.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Callable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;
import software.amazon.awssdk.testutils.EnvironmentVariableHelper;
import software.amazon.awssdk.utils.Validate;

@RunWith(Parameterized.class)
public class RetryModeTest {
    private static final EnvironmentVariableHelper ENVIRONMENT_VARIABLE_HELPER = new EnvironmentVariableHelper();

    @Parameterized.Parameter
    public TestData testData;

    @Parameterized.Parameters
    public static Collection<Object> data() {
        return Arrays.asList(new Object[] {
            // Test defaults
            new TestData(null, null, null, null, RetryMode.LEGACY),
            new TestData(null, null, "PropertyNotSet", null, RetryMode.LEGACY),

            // Test precedence
            new TestData("standard", "legacy", "PropertySetToLegacy", RetryMode.LEGACY, RetryMode.STANDARD),
            new TestData("standard", null, null, RetryMode.LEGACY, RetryMode.STANDARD),
            new TestData(null, "standard", "PropertySetToLegacy", RetryMode.LEGACY, RetryMode.STANDARD),
            new TestData(null, "standard", null, RetryMode.LEGACY, RetryMode.STANDARD),
            new TestData(null, null, "PropertySetToStandard", RetryMode.LEGACY, RetryMode.STANDARD),
            new TestData(null, null, null, RetryMode.STANDARD, RetryMode.STANDARD),

            // Test invalid values
            new TestData("wrongValue", null, null, null, IllegalStateException.class),
            new TestData(null, "wrongValue", null, null, IllegalStateException.class),
            new TestData(null, null, "PropertySetToUnsupportedValue", null, IllegalStateException.class),

            // Test capitalization standardization
            new TestData("sTaNdArD", null, null, null, RetryMode.STANDARD),
            new TestData(null, "sTaNdArD", null, null, RetryMode.STANDARD),
            new TestData(null, null, "PropertyMixedCase", null, RetryMode.STANDARD),
            });
    }

    @Before
    @After
    public void methodSetup() {
        ENVIRONMENT_VARIABLE_HELPER.reset();
        System.clearProperty(SdkSystemSetting.AWS_RETRY_MODE.property());
        System.clearProperty(ProfileFileSystemSetting.AWS_PROFILE.property());
        System.clearProperty(ProfileFileSystemSetting.AWS_CONFIG_FILE.property());
    }

    @Test
    public void differentCombinationOfConfigs_shouldResolveCorrectly() throws Exception {
        if (testData.envVarValue != null) {
            ENVIRONMENT_VARIABLE_HELPER.set(SdkSystemSetting.AWS_RETRY_MODE.environmentVariable(), testData.envVarValue);
        }

        if (testData.systemProperty != null) {
            System.setProperty(SdkSystemSetting.AWS_RETRY_MODE.property(), testData.systemProperty);
        }

        if (testData.configFile != null) {
            String diskLocationForFile = diskLocationForConfig(testData.configFile);
            Validate.isTrue(Files.isReadable(Paths.get(diskLocationForFile)), diskLocationForFile + " is not readable.");
            System.setProperty(ProfileFileSystemSetting.AWS_PROFILE.property(), "default");
            System.setProperty(ProfileFileSystemSetting.AWS_CONFIG_FILE.property(), diskLocationForFile);
        }

        Callable<RetryMode> result = RetryMode.resolver().defaultRetryMode(testData.defaultMode)::resolve;
        if (testData.expected instanceof Class<?>) {
            Class<?> expectedClassType = (Class<?>) testData.expected;
            assertThatThrownBy(result::call).isInstanceOf(expectedClassType);
        } else {
            assertThat(result.call()).isEqualTo(testData.expected);
        }
    }

    private String diskLocationForConfig(String configFileName) {
        return getClass().getResource(configFileName).getFile();
    }


    private static class TestData {
        private final String envVarValue;
        private final String systemProperty;
        private final String configFile;
        private final RetryMode defaultMode;
        private final Object expected;

        TestData(String systemProperty, String envVarValue, String configFile, RetryMode defaultMode, Object expected) {
            this.envVarValue = envVarValue;
            this.systemProperty = systemProperty;
            this.configFile = configFile;
            this.defaultMode = defaultMode;
            this.expected = expected;
        }
    }
}