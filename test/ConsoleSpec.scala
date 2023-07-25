/**
 * This file is part of ballot_box.
 * Copyright (C) 2017  Sequent Tech Inc <legal@sequentech.io>

 * ballot_box is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * ballot_box  is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.
**/

package test

import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.libs.json._
import play.api.mvc.Action
import play.api.mvc.Results._
import play.api.test._
import commands._
import models._
import utils.JsonFormatters._
import utils.Crypto
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._
import play.api.cache.Cache
import java.sql.Timestamp

import play.api.libs.Files._
import java.nio.file.{Paths, Files, Path}
import java.nio.file.StandardOpenOption._
import java.nio.charset.StandardCharsets


@RunWith(classOf[JUnitRunner])
class ConsoleSpec extends Specification with TestContexts
{
  val timeoutDuration = Duration(20, SECONDS)
  val dtoStr =
"""{
  "id": 42,
  "configuration": {
    "id": 42,
    "layout": "simple",
    "director": "auth1",
    "authorities": [
      "auth2"
    ],
    "title": "New election",
    "description": "",
    "questions": [
      {
        "description": "",
        "layout": "accordion",
        "max": 2,
        "min": 1,
        "num_winners": 1,
        "title": "New question 0",
        "tally_type": "plurality-at-large",
        "answer_total_votes_percentage": "over-total-valid-votes",
        "answers": [
          {
            "id": 0,
            "category": "",
            "details": "this is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long description",
            "sort_order": 0,
            "urls": [],
            "text": "ab"
          },
          {
            "id": 1,
            "category": "",
            "details": "this is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long description",
            "sort_order": 1,
            "urls": [],
            "text": "cd"
          }
        ],
        "extra_options": {
          "shuffle_categories": true,
          "shuffle_all_options": true,
          "shuffle_category_list": [],
          "show_points": false
        }
      }
    ],
    "start_date": "2015-01-27T16:00:00.001",
    "end_date": "2015-01-27T16:00:00.001",
    "presentation": {
      "share_text": [
        {
          "network": "Twitter",
          "button_text": "",
          "social_message": "I have just voted in election https://go.sequentech.io, you can too! #sequent"
        }
      ],
      "theme": "default",
      "urls": [],
      "theme_css": ""
    },
    "real": false,
    "extra_data": "{}",
    "virtual": false,
    "tally_allowed": true,
    "publicCandidates": true,
    "virtualSubelections": [],
    "logo_url": "",
    "trusteeKeysState": []
  },
  "state": "created",
  "tally_state": "no_tally",
  "startDate": "2015-01-27T16:00:00.001",
  "endDate": "2015-01-27T16:00:00.001",
  "pks": "[{\"q\":\"24792774508736884642868649594982829646677044143456685966902090450389126928108831401260556520412635107010557472033959413182721740344201744439332485685961403243832055703485006331622597516714353334475003356107214415133930521931501335636267863542365051534250347372371067531454567272385185891163945756520887249904654258635354225185183883072436706698802915430665330310171817147030511296815138402638418197652072758525915640803066679883309656829521003317945389314422254112846989412579196000319352105328237736727287933765675623872956765501985588170384171812463052893055840132089533980513123557770728491280124996262883108653723\",\"p\":\"49585549017473769285737299189965659293354088286913371933804180900778253856217662802521113040825270214021114944067918826365443480688403488878664971371922806487664111406970012663245195033428706668950006712214428830267861043863002671272535727084730103068500694744742135062909134544770371782327891513041774499809308517270708450370367766144873413397605830861330660620343634294061022593630276805276836395304145517051831281606133359766619313659042006635890778628844508225693978825158392000638704210656475473454575867531351247745913531003971176340768343624926105786111680264179067961026247115541456982560249992525766217307447\",\"y\":\"36546166940599313416168254217744697729911998485163232812421755063790894563031509201724580104715842554805784888514324341999107803878734424284146217463747343381092445557201708138031676925435834811853723665739792579746520854169833006814210270893803218238156148054483205249064331467974912676179467375074903261138777633846359449221132575280279543204461738096325062062234143265942024786615427875783393149444592255523696808235191395788053253235712555210828055008777307102133087648043115255896201502913954041965429673426222803318582002444673454964248205507662853386137487981094735018694462667314177271930377544606339760319819\",\"g\":\"27257469383433468307851821232336029008797963446516266868278476598991619799718416119050669032044861635977216445034054414149795443466616532657735624478207460577590891079795564114912418442396707864995938563067755479563850474870766067031326511471051504594777928264027177308453446787478587442663554203039337902473879502917292403539820877956251471612701203572143972352943753791062696757791667318486190154610777475721752749567975013100844032853600120195534259802017090281900264646220781224136443700521419393245058421718455034330177739612895494553069450438317893406027741045575821283411891535713793639123109933196544017309147\"}]",
  "real": false,
  "virtual": false,
  "tallyAllowed": true,
  "publicCandidates": true,
  "logo_url": "",
  "segmentedMixing": false,
  "trusteeKeysState": []
}"""
  val dto : ElectionDTO = Json.parse(dtoStr).validate[ElectionDTO].get

  "parse_args" should
  {
    "read nothing with only one argument" in
    {
      val console = new ConsoleImpl()
      val array: Array[String] = Array("console")
      console.parse_args(array) must be equalTo(0)
    }

    "read nothing with only two arguments" in
    {
      val console = new ConsoleImpl()
      val array: Array[String] = Array("console", "whatever")
      console.parse_args(array) must be equalTo(0)
    }

    "throw IllegalArgumentException with wrong arguments" in
    {
      val console = new ConsoleImpl()
      val array: Array[String] = Array("console", "--whatever", "value")
      console.parse_args(array) must throwAn[java.lang.IllegalArgumentException]
    }

    "throw IllegalArgumentException with invalid port" in
    {
      val console = new ConsoleImpl()
      var array: Array[String] = Array("gen_votes", "--port", "value")
      console.parse_args(array) must throwAn[java.lang.IllegalArgumentException]
      array = Array("gen_votes", "--port", "0")
      console.parse_args(array) must throwAn
      array = Array("gen_votes", "--port", "65536")
      console.parse_args(array) must throwAn[java.lang.IllegalArgumentException]
      array = Array("gen_votes", "--port", "555555")
      console.parse_args(array) must throwAn[java.lang.IllegalArgumentException]
    }

    "read port with valid port" in
    {
      val console = new ConsoleImpl()
      var array: Array[String] = Array("console",  "--port", "40")
      console.parse_args(array) must be equalTo(2)
      console.port must be equalTo(40)
      array = Array("gen_votes", "--port", "65535")
      console.parse_args(array) must be equalTo(2)
      console.port must be equalTo(65535)
    }
  } // parse_args

  "processPlaintextLine" should
  {
    "throw error on invalid line" in
    {
      val console = new ConsoleImpl()
      console.processPlaintextLine("", 0) must throwAn[PlaintextError]
      console.processPlaintextLine("d", 0) must throwAn[PlaintextError]
      console.processPlaintextLine("1122", 0) must throwAn[PlaintextError]
    }

    "process line with valid blank vote" in
    {
      val console = new ConsoleImpl()
      var ballot = console.processPlaintextLine("14|", 0)
      Json.toJson(ballot).toString must be equalTo("""{"id":14,"answers":[{"options":[]}]}""")
    }

    "process line with valid vote" in
    {
      val console = new ConsoleImpl()
      var ballot = console.processPlaintextLine("4888774|0,1,29||3", 0)
      Json.toJson(ballot).toString must be equalTo("""{"id":4888774,"answers":[{"options":[0,1,29]},{"options":[]},{"options":[3]}]}""")
    }
  } // processPlaintextLine

  "get_khmac" should
  {
    "work" in
    {
      val console = new ConsoleImpl()
      console.shared_secret = "<PASSWORD>"
      val khmac = console.get_khmac("user_id", "obj_type", 11, "perms", Some(22))
      khmac must be equalTo(s"khmac:///sha-256;1ba07fd2e1becf9adfe74b4f6e0814fb4c9af3e0a920e718cec287857b480856/user_id:obj_type:11:perms:22")
    }
  } // get_khmac

  "get_election_info and get_election_info_all" should
  {
    val appWithRoutes = FakeApplication(withRoutes =
    {
      case ("GET", "/honolulu/api/election/24") =>
        Action
        {
          val response = """{"date":"2017-04-03 09:21:04.923","payload":{"id":24,"configuration":{"id":24,"publicCandidates": true,"layout":"simple","director":"auth1","authorities":["auth2"],"title":"New election","description":"","questions":[{"description":"","layout":"accordion","max":1,"min":1,"num_winners":1,"title":"New question 0","tally_type":"plurality-at-large","answer_total_votes_percentage":"over-total-valid-votes","answers":[{"id":0,"category":"","details":"this is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long description","sort_order":0,"urls":[],"text":"ab"},{"id":1,"category":"","details":"this is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long description","sort_order":1,"urls":[],"text":"cd"}],"extra_options":{"shuffle_categories":true,"shuffle_all_options":true,"shuffle_category_list":[],"show_points":false}}],"start_date":"2015-01-27T16:00:00.001","end_date":"2015-01-27T16:00:00.001","presentation":{"share_text":[{"network":"Twitter","button_text":"","social_message":"I have just voted in election https://go.sequentech.io, you can too! #sequent"}],"theme":"default","urls":[],"theme_css":""},"real":false,"extra_data":"{}","virtual":false,"virtualSubelections":[],"logo_url":"","trusteeKeysState": []},"state":"created","tally_state":"no_tally","startDate":"2015-01-27T16:00:00.001","endDate":"2015-01-27T16:00:00.001","pks":"[{\"q\":\"24792774508736884642868649594982829646677044143456685966902090450389126928108831401260556520412635107010557472033959413182721740344201744439332485685961403243832055703485006331622597516714353334475003356107214415133930521931501335636267863542365051534250347372371067531454567272385185891163945756520887249904654258635354225185183883072436706698802915430665330310171817147030511296815138402638418197652072758525915640803066679883309656829521003317945389314422254112846989412579196000319352105328237736727287933765675623872956765501985588170384171812463052893055840132089533980513123557770728491280124996262883108653723\",\"p\":\"49585549017473769285737299189965659293354088286913371933804180900778253856217662802521113040825270214021114944067918826365443480688403488878664971371922806487664111406970012663245195033428706668950006712214428830267861043863002671272535727084730103068500694744742135062909134544770371782327891513041774499809308517270708450370367766144873413397605830861330660620343634294061022593630276805276836395304145517051831281606133359766619313659042006635890778628844508225693978825158392000638704210656475473454575867531351247745913531003971176340768343624926105786111680264179067961026247115541456982560249992525766217307447\",\"y\":\"36546166940599313416168254217744697729911998485163232812421755063790894563031509201724580104715842554805784888514324341999107803878734424284146217463747343381092445557201708138031676925435834811853723665739792579746520854169833006814210270893803218238156148054483205249064331467974912676179467375074903261138777633846359449221132575280279543204461738096325062062234143265942024786615427875783393149444592255523696808235191395788053253235712555210828055008777307102133087648043115255896201502913954041965429673426222803318582002444673454964248205507662853386137487981094735018694462667314177271930377544606339760319819\",\"g\":\"27257469383433468307851821232336029008797963446516266868278476598991619799718416119050669032044861635977216445034054414149795443466616532657735624478207460577590891079795564114912418442396707864995938563067755479563850474870766067031326511471051504594777928264027177308453446787478587442663554203039337902473879502917292403539820877956251471612701203572143972352943753791062696757791667318486190154610777475721752749567975013100844032853600120195534259802017090281900264646220781224136443700521419393245058421718455034330177739612895494553069450438317893406027741045575821283411891535713793639123109933196544017309147\"}]","real":false,"virtual":false,"publicCandidates": true,"segmentedMixing": false,"logo_url":"","trusteeKeysState": []}}"""
          Ok(response)
        }
      case ("GET", "/honolulu/api/election/25") =>
        Action
        {
          NotFound("ERROR")
        }
    })

    // FIXME: Disable this test because it tries to request to connect
    // localhost:5432 instead of use the test database
    //
    // "work (get_election_info)" in new WithServer(app = appWithRoutes, port = 5433)
    // {

    //   val console = new ConsoleImpl()
    //   console.http_type = "http"
    //   console.host = "localhost"
    //   console.port = 5433
    //   console.service_path = "honolulu/"
    //   val dtoFuture24 : Future[String] =
    //     console.get_election_info(24) map
    //     {
    //       dto =>
    //         Json.toJson(dto).toString
    //     }
    //   val dtoFuture25 : Future[String] =
    //     console.get_election_info(25) map
    //     {
    //       dto =>
    //         Json.toJson(dto).toString
    //     }
    //   Await.ready(dtoFuture24, timeoutDuration).value.get must beSuccessfulTry
    //   Await.ready(dtoFuture25, timeoutDuration).value.get must beFailedTry
    // }
  } // get_election_info and get_election_info_all

  "dump_pks_elections/dump_pks" should
  {
    val appWithRoutes = FakeApplication(withRoutes =
    {
      case ("POST", "/honolulu/api/election/24/dump-pks") =>
        Action
        {
          Ok("whatever")
        }
      case ("POST", "/honolulu/api/election/25/dump-pks") =>
        Action
        {
          NotFound("ERROR")
        }
    })

    // FIXME: Disable this test because it tries to request to connect
    // localhost:5432 instead of use the test database
    //
    // "work (dump_pks)" in new WithServer(app = appWithRoutes, port = 5433)
    // {
    //   val console = new ConsoleImpl()
    //   console.http_type = "http"
    //   console.host = "localhost"
    //   console.port = 5433
    //   console.service_path = "honolulu/"
    //   val dtoFuture24 = console.dump_pks(24)
    //   val dtoFuture25 = console.dump_pks(25)
    //   Await.ready(dtoFuture24, timeoutDuration).value.get must beSuccessfulTry
    //   Await.ready(dtoFuture25, timeoutDuration).value.get must beFailedTry
    // }
  } // dump_pks_elections/dump_pks

  "encodePlaintext" should
  {
    "work" in
    {
      val console = new ConsoleImpl()
      console.http_type = "http"
      console.host = "localhost"
      console.port = 5433
      console.service_path = "honolulu/"
      val ballot: PlaintextBallot =
        PlaintextBallot(
          42L,
          Array(
            PlaintextAnswer(
              Array[Long](0L,1L))))
       console.encodePlaintext(ballot, dto).map{ num : BigInt => num.toLong.toString } must beEqualTo(Array("12"))
    }
  } // encodePlaintext

  "gen_rnd_str" should
  {
    "generate random strings" in
    {
      val console = new ConsoleImpl()
      val rnd1_len2 = console.gen_rnd_str(2, "ab")
      val rnd2_len2 = console.gen_rnd_str(2, "abcdefg1234567890")
      val rnd_len200 = console.gen_rnd_str(200, "abcdefg1234567890")

      rnd1_len2.length must beEqualTo(2)
      rnd2_len2.length must beEqualTo(2)
      rnd_len200.length must beEqualTo(200)
      rnd1_len2.matches("[ab]*") must beEqualTo(true)
      rnd2_len2.matches("[abcdefg1234567890]*") must beEqualTo(true)
      rnd_len200.matches("[abcdefg1234567890]*") must beEqualTo(true)
    }
  } // gen_rnd_str

  "generate_voterid" should
  {
    "generate random voter ids" in
    {
      val console = new ConsoleImpl()
      console.voterid_len = 2
      val rnd_len2 = console.generate_voterid()
      console.voterid_len = 200
      val rnd_len200 = console.generate_voterid()

      rnd_len2.length must beEqualTo(2)
      rnd_len200.length must beEqualTo(200)
      rnd_len2.matches(s"[${console.voterid_alphabet}]*") must beEqualTo(true)
      rnd_len200.matches(s"[${console.voterid_alphabet}]*") must beEqualTo(true)
    }
  } // generate_voterid

  "generate_vote_line" should
  {
    val console = new ConsoleImpl()
    val ballot: PlaintextBallot =
      PlaintextBallot(
        42L,
        Array(
          PlaintextAnswer(
            Array[Long](0L,1L))))
    val electionsInfoMap =  scala.collection.mutable.HashMap[Long, ElectionDTO]( (42L -> dto) )
    val pksMap = console.get_pks_map(electionsInfoMap)
    val pks = pksMap.get(42).get

    "work" in {
       val encodedBallot = console.encodePlaintext( ballot, dto )
       val encryptedBallot = Crypto.encryptBig(pks, encodedBallot)
       val line = console.generate_vote_line(42, encryptedBallot)
       val match_regex = s"""42\\|[${console.voterid_alphabet}]+\\|\\{"vote":"\\{\\\\"choices\\\\":\\[\\{\\\\"alpha\\\\":\\\\"[0-9]+\\\\",\\\\"beta\\\\":\\\\"[0-9]+\\\\"\\}\\],\\\\"issue_date\\\\":\\\\"now\\\\",\\\\"proofs\\\\":\\[\\{\\\\"challenge\\\\":\\\\"[0-9]+\\\\",\\\\"commitment\\\\":\\\\"[0-9]+\\\\",\\\\"response\\\\":\\\\"[0-9]+\\\\"\\}\\]\\}","vote_hash":"[0-9a-f]+"\\}\n"""
       line.matches(match_regex) must beEqualTo(true)
    }
  } // generate_vote_line

  "get_pks_map" should {

    "work" in {
      val console = new ConsoleImpl()
      val electionsInfoMap =  scala.collection.mutable.HashMap[Long, ElectionDTO]( (42L -> dto) )
      val pksMap = console.get_pks_map(electionsInfoMap)
      pksMap.get(45).isEmpty must beEqualTo(true)
      pksMap.get(42).isEmpty must beEqualTo(false)
    }
  } // get_pks_map

  "generate_plaintexts_for_eid" should {
    "work" in {
      val console = new ConsoleImpl()
      val plaintexts : String = console.generate_plaintexts_for_eid(42, 100, dto)
      val regex = "[0-9]+(\\|([0-9]+,)*([0-9]+)?)*"
      val linesArray = plaintexts.split("\n")
      val matchesRegex = linesArray.map{ line => line.matches(regex)}.reduce(_ && _)
      linesArray.size must beEqualTo(100)
      matchesRegex must beEqualTo(true)
    }
  }

  "generate_map_num_votes_dto" should {
    val dtoStr55 =
"""{
  "id": 55,
  "configuration": {
    "id": 55,
    "layout": "simple",
    "director": "auth1",
    "authorities": [
      "auth2"
    ],
    "title": "New election",
    "description": "",
    "questions": [
      {
        "description": "",
        "layout": "accordion",
        "max": 2,
        "min": 1,
        "num_winners": 1,
        "title": "New question 0",
        "tally_type": "plurality-at-large",
        "answer_total_votes_percentage": "over-total-valid-votes",
        "answers": [
          {
            "id": 0,
            "category": "",
            "details": "this is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long description",
            "sort_order": 0,
            "urls": [],
            "text": "ab"
          },
          {
            "id": 1,
            "category": "",
            "details": "this is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long descriptionthis is a long description",
            "sort_order": 1,
            "urls": [],
            "text": "cd"
          }
        ],
        "extra_options": {
          "shuffle_categories": true,
          "shuffle_all_options": true,
          "shuffle_category_list": [],
          "show_points": false
        }
      }
    ],
    "start_date": "2015-01-27T16:00:00.001",
    "end_date": "2015-01-27T16:00:00.001",
    "presentation": {
      "share_text": [
        {
          "network": "Twitter",
          "button_text": "",
          "social_message": "I have just voted in election https://go.sequentech.io, you can too! #sequent"
        }
      ],
      "theme": "default",
      "urls": [],
      "theme_css": ""
    },
    "real": false,
    "extra_data": "{}",
    "virtual": false,
    "tally_allowed": true,
    "publicCandidates": true,
    "virtualSubelections": [],
    "logo_url": "",
    "trusteeKeysState": []
  },
  "state": "created",
  "startDate": "2015-01-27T16:00:00.001",
  "endDate": "2015-01-27T16:00:00.001",
  "pks": "[{\"q\":\"24792774508736884642868649594982829646677044143456685966902090450389126928108831401260556520412635107010557472033959413182721740344201744439332485685961403243832055703485006331622597516714353334475003356107214415133930521931501335636267863542365051534250347372371067531454567272385185891163945756520887249904654258635354225185183883072436706698802915430665330310171817147030511296815138402638418197652072758525915640803066679883309656829521003317945389314422254112846989412579196000319352105328237736727287933765675623872956765501985588170384171812463052893055840132089533980513123557770728491280124996262883108653723\",\"p\":\"49585549017473769285737299189965659293354088286913371933804180900778253856217662802521113040825270214021114944067918826365443480688403488878664971371922806487664111406970012663245195033428706668950006712214428830267861043863002671272535727084730103068500694744742135062909134544770371782327891513041774499809308517270708450370367766144873413397605830861330660620343634294061022593630276805276836395304145517051831281606133359766619313659042006635890778628844508225693978825158392000638704210656475473454575867531351247745913531003971176340768343624926105786111680264179067961026247115541456982560249992525766217307447\",\"y\":\"36546166940599313416168254217744697729911998485163232812421755063790894563031509201724580104715842554805784888514324341999107803878734424284146217463747343381092445557201708138031676925435834811853723665739792579746520854169833006814210270893803218238156148054483205249064331467974912676179467375074903261138777633846359449221132575280279543204461738096325062062234143265942024786615427875783393149444592255523696808235191395788053253235712555210828055008777307102133087648043115255896201502913954041965429673426222803318582002444673454964248205507662853386137487981094735018694462667314177271930377544606339760319819\",\"g\":\"27257469383433468307851821232336029008797963446516266868278476598991619799718416119050669032044861635977216445034054414149795443466616532657735624478207460577590891079795564114912418442396707864995938563067755479563850474870766067031326511471051504594777928264027177308453446787478587442663554203039337902473879502917292403539820877956251471612701203572143972352943753791062696757791667318486190154610777475721752749567975013100844032853600120195534259802017090281900264646220781224136443700521419393245058421718455034330177739612895494553069450438317893406027741045575821283411891535713793639123109933196544017309147\"}]",
  "real": false,
  "virtual": false,
  "publicCandidates": true,
  "tallyAllowed": true,
  "segmentedMixing": false,
  "logo_url": "",
  "trusteeKeysState": []
}"""
    val dto55 : ElectionDTO = Json.parse(dtoStr55).validate[ElectionDTO].get
    "work" in {
      val console = new ConsoleImpl()
      val electionsInfoMap =
        scala.collection.mutable.HashMap[Long, ElectionDTO](
          (42L -> dto),
          (55L -> dto55))
      console.vote_count = 5
      val numVotesMap = console.generate_map_num_votes_dto(electionsInfoMap)
      numVotesMap.get(42).get._1 must beEqualTo(3)
      numVotesMap.get(55).get._1 must beEqualTo(2)
    }
  } // generate_map_num_votes_dto

  "getElectionsSet" should {
    "work" in {
      val tempFile = TemporaryFile(prefix = "election_ids", suffix = ".txt")
      val filePath = tempFile.file.getAbsolutePath()
      val eidsContent = "42\n55"
      Files.write(Paths.get(filePath), eidsContent.getBytes(StandardCharsets.UTF_8), APPEND)
      val console = new ConsoleImpl()
      console.election_ids_path = filePath
      val futureElectionSet = console.getElectionsSet()
      val optionElectionSet = Await.ready(futureElectionSet, timeoutDuration).value

      optionElectionSet.get must beSuccessfulTry
      val electionSet = optionElectionSet.get.get
      electionSet.contains(42) must beEqualTo(true)
      electionSet.contains(55) must beEqualTo(true)
      electionSet.size must beEqualTo(2)
    } // getElectionsSet
  }

} // ConsoleSpec
