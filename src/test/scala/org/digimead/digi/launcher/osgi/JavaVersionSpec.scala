/**
 * Digi-Launcher - OSGi framework launcher for Equinox environment.
 *
 * Copyright (c) 2014 Alexey Aksenov ezh@ezh.msk.ru
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.digimead.digi.launcher.osgi

import org.digimead.digi.lib.DependencyInjection
import org.digimead.digi.lib.log.api.XLoggable
import org.digimead.lib.test.LoggingHelper
import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.mock.MockitoSugar

class JavaVersionSpec extends WordSpec with LoggingHelper with Matchers with MockitoSugar with XLoggable {
  before { DependencyInjection(org.digimead.digi.lib.default, false) }

  "JavaVersion toVersion" must {
    "have proper behaviour" in {
      JavaVersion.toVersion("1.6.0_30") should be(JavaVersion(6, true))
      JavaVersion.toVersion("1.7.0_10") should be(JavaVersion(7, true))
      JavaVersion.toVersion("1.8.0_05") should be(JavaVersion(8, true))

    }
  }

  override def beforeAll(configMap: org.scalatest.ConfigMap) { adjustLoggingBeforeAll(configMap) }
}