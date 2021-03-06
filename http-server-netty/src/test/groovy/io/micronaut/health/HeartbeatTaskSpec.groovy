/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.health

import io.micronaut.context.ApplicationContext
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.inject.Singleton

/**
 * @author graemerocher
 * @since 1.0
 */
class HeartbeatTaskSpec extends Specification {

    @IgnoreIf({System.getenv('TRAVIS')})
    void "test that by default a heartbeat is sent"() {
        when:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.heartbeat.initialDelay':'1ms',
                'micronaut.heartbeat.interval':'10ms',
                'micronaut.application.name':'test'
        ])
        HeartbeatListener listener = embeddedServer.getApplicationContext().getBean(HeartbeatListener)
        PollingConditions conditions = new PollingConditions(timeout: 5, delay: 0.5)

        then:
        conditions.eventually {
            listener.event != null
        }

        cleanup:
        embeddedServer.stop()
    }


    @Singleton
    static class HeartbeatListener implements ApplicationEventListener<HeartbeatEvent> {
        HeartbeatEvent event
        @Override
        void onApplicationEvent(HeartbeatEvent event) {
            this.event = event
        }
    }
}
