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
package com.waz.utils

import com.waz.Generators._
import com.waz.model.Uid
import com.waz.content.Preference.PrefCodec._
import com.waz.znet.AuthenticationManager.Token

import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.threeten.bp.Instant


class KeyValuePrefSpec extends FeatureSpec with Matchers with GeneratorDrivenPropertyChecks {

  feature("key value serialization") {
    scenario("boolean serialization") {
      forAll { o: Boolean => BooleanCodec.decode(BooleanCodec.encode(o)) shouldEqual o }
    }

    scenario("int serialization") {
      forAll { i: Int => IntCodec.decode(IntCodec.encode(i)) shouldEqual i }
    }

    scenario("long serialization") {
      forAll { l: Long => LongCodec.decode(LongCodec.encode(l)) shouldEqual l }
    }

    scenario("token serialization") {
      forAll { t: Option[Token] => TokenCodec.decode(TokenCodec.encode(t)) shouldEqual t }
    }

    scenario("instant serialization") {
      forAll { i: Instant => InstantCodec.decode(InstantCodec.encode(i)) shouldEqual i }
    }

    scenario("uid serialization") {
      forAll { uid: Uid => idCodec[Uid].decode(idCodec[Uid].encode(uid)) shouldEqual uid }
    }
  }
}
