/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.api.impl

import org.scalatest.{FeatureSpec, Matchers, RobolectricTests}

import scala.util.Random

class UsernamesSpec extends FeatureSpec with Matchers  with RobolectricTests {
  val email = "test@test.com"
  val password = "password"
  val wireMockPort = 9000 + Random.nextInt(3000)

  val usernames = new Usernames(null)

    Random.setSeed(0)

    scenario ("Username u should be invalid") {
      usernames.isUsernameValid("u").isValid should be(false)
    }

    scenario ("Username pokemon_master354 should be valid") {
      usernames.isUsernameValid("pokemon_master354").isValid should be(true)
    }

    scenario ("Username CatZ_MasteR should be invalid") {
      usernames.isUsernameValid("CatZ_MasteR").isValid should be(false)
    }

    scenario ("Username shiny+ufo should be invalid") {
      usernames.isUsernameValid("shiny+ufo").isValid should be(false)
    }

    scenario ("Username super_long_username_because_whatever should be invalid") {
      usernames.isUsernameValid("super_long_username_because_whatever").isValid should be(false)
    }

    scenario ("Username \uD83D\uDE3C孟利 should be invalid") {
      usernames.isUsernameValid("\uD83D\uDE3C孟利").isValid should be(false)
    }

    scenario ("Username generation with latin characters only") {
      val genName = usernames.generateUsernameFromName("Wire", null)
      genName should be("wire")
    }

    scenario ("Username generation with latin characters and space") {
      val genName = usernames.generateUsernameFromName("Wire Wireson", null)
      genName should be("wirewireson")
    }

    scenario ("Username generation with latin characters from extended alphabet") {
      val genName = usernames.generateUsernameFromName("Æéÿüíøšłźçñ", null)
      genName should be("aeyuioslzcn")
    }

    scenario ("Username generation with emojis only") {
      val genName = usernames.generateUsernameFromName("\uD83D\uDE3C", null)
      genName should be("")
    }

    scenario ("Username generation with cyrillic characters") {
      val genName = usernames.generateUsernameFromName("Даша", null)
      genName should be("")
    }

    scenario ("Username generation with arabic characters") {
      val genName = usernames.generateUsernameFromName("داريا", null)
      genName should be("")
    }

    scenario ("Username generation with chinese characters") {
      val genName = usernames.generateUsernameFromName("孟利", null)
      genName should be("")
    }

}
