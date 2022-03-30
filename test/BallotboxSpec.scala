/**
 * This file is part of ballot_box.
 * Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

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

import controllers.routes
import utils.Response
import utils.JsonFormatters._

import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.test._
import play.api.test.Helpers._
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.JsSuccess

import scala.util.Try
import scala.util.Failure
import scala.util.Success
import utils._

import scala.concurrent._
import scala.concurrent.duration._

import models._
import controllers._
import play.api.db.slick.DB

// FIXME add hash check test
@RunWith(classOf[JUnitRunner])
class BallotboxSpec extends Specification with TestContexts with Response {

  val pks1020 = """[{"q": "24792774508736884642868649594982829646677044143456685966902090450389126928108831401260556520412635107010557472033959413182721740344201744439332485685961403243832055703485006331622597516714353334475003356107214415133930521931501335636267863542365051534250347372371067531454567272385185891163945756520887249904654258635354225185183883072436706698802915430665330310171817147030511296815138402638418197652072758525915640803066679883309656829521003317945389314422254112846989412579196000319352105328237736727287933765675623872956765501985588170384171812463052893055840132089533980513123557770728491280124996262883108653723", "p": "49585549017473769285737299189965659293354088286913371933804180900778253856217662802521113040825270214021114944067918826365443480688403488878664971371922806487664111406970012663245195033428706668950006712214428830267861043863002671272535727084730103068500694744742135062909134544770371782327891513041774499809308517270708450370367766144873413397605830861330660620343634294061022593630276805276836395304145517051831281606133359766619313659042006635890778628844508225693978825158392000638704210656475473454575867531351247745913531003971176340768343624926105786111680264179067961026247115541456982560249992525766217307447", "y": "13508098908027539440784872565963852589801134315490214338943176764836483642826139287845321515683717120380306277154055628326294507608619219418615557143216499594289645318240026038566886829367592524471392914903704781226769279964504637233468503819400275780389692993402172532069322059466650168544610528332541448517737477349241892135854753287819378120770750997761642638894924410113312379756439635652045600801662648509996855951194816645263112222062553013343235646559457633821952444151434434089012336057352241599491187136434213738494446172709662424227966766414134346388055354268602096835376151079526673696516280553593666107589", "g": "27257469383433468307851821232336029008797963446516266868278476598991619799718416119050669032044861635977216445034054414149795443466616532657735624478207460577590891079795564114912418442396707864995938563067755479563850474870766067031326511471051504594777928264027177308453446787478587442663554203039337902473879502917292403539820877956251471612701203572143972352943753791062696757791667318486190154610777475721752749567975013100844032853600120195534259802017090281900264646220781224136443700521419393245058421718455034330177739612895494553069450438317893406027741045575821283411891535713793639123109933196544017309147"}, {"q": "24792774508736884642868649594982829646677044143456685966902090450389126928108831401260556520412635107010557472033959413182721740344201744439332485685961403243832055703485006331622597516714353334475003356107214415133930521931501335636267863542365051534250347372371067531454567272385185891163945756520887249904654258635354225185183883072436706698802915430665330310171817147030511296815138402638418197652072758525915640803066679883309656829521003317945389314422254112846989412579196000319352105328237736727287933765675623872956765501985588170384171812463052893055840132089533980513123557770728491280124996262883108653723", "p": "49585549017473769285737299189965659293354088286913371933804180900778253856217662802521113040825270214021114944067918826365443480688403488878664971371922806487664111406970012663245195033428706668950006712214428830267861043863002671272535727084730103068500694744742135062909134544770371782327891513041774499809308517270708450370367766144873413397605830861330660620343634294061022593630276805276836395304145517051831281606133359766619313659042006635890778628844508225693978825158392000638704210656475473454575867531351247745913531003971176340768343624926105786111680264179067961026247115541456982560249992525766217307447", "y": "31297029205822268885109934561544608602226551039112423096402549637947957272731991384490183392409022722612106620608611126891966831755090546449989893634300800079970667005150302578667091006497824837222532245847901951555174396950008610097420473924217521376861890531635822604735534162871386978450992468493031003499877489309748655981166737937456509501504493651931555406164617066203407040999511236599399729264489639078620839280286466056838131704329524969392869702643746439900685050950772288960536343109589179956887355431627048809981098998158435159660104072149990254799572045096649738379551835785845681480411449382487219653409", "g": "27257469383433468307851821232336029008797963446516266868278476598991619799718416119050669032044861635977216445034054414149795443466616532657735624478207460577590891079795564114912418442396707864995938563067755479563850474870766067031326511471051504594777928264027177308453446787478587442663554203039337902473879502917292403539820877956251471612701203572143972352943753791062696757791667318486190154610777475721752749567975013100844032853600120195534259802017090281900264646220781224136443700521419393245058421718455034330177739612895494553069450438317893406027741045575821283411891535713793639123109933196544017309147"}, {"q": "24792774508736884642868649594982829646677044143456685966902090450389126928108831401260556520412635107010557472033959413182721740344201744439332485685961403243832055703485006331622597516714353334475003356107214415133930521931501335636267863542365051534250347372371067531454567272385185891163945756520887249904654258635354225185183883072436706698802915430665330310171817147030511296815138402638418197652072758525915640803066679883309656829521003317945389314422254112846989412579196000319352105328237736727287933765675623872956765501985588170384171812463052893055840132089533980513123557770728491280124996262883108653723", "p": "49585549017473769285737299189965659293354088286913371933804180900778253856217662802521113040825270214021114944067918826365443480688403488878664971371922806487664111406970012663245195033428706668950006712214428830267861043863002671272535727084730103068500694744742135062909134544770371782327891513041774499809308517270708450370367766144873413397605830861330660620343634294061022593630276805276836395304145517051831281606133359766619313659042006635890778628844508225693978825158392000638704210656475473454575867531351247745913531003971176340768343624926105786111680264179067961026247115541456982560249992525766217307447", "y": "28153039221047374758267050742258469188407714375035938721532943393478730897565403536508107271034470161491862917467036671234191594061901743326054047906010029686746918292239208850818204556361759410481858274118021155554294796620103237004461685393187877457724113104419448494181862271526366233662587437547960647309797858777370591388933819508188041869180927498771832754947955323240717585941583632417751853012192483838974392147015304758036865840584555098871789975977943566262267427057405787758933461905202016456744705696313896056687561449718558140672912358532178330002601533819297718750337836014564907646333413658482178522028", "g": "27257469383433468307851821232336029008797963446516266868278476598991619799718416119050669032044861635977216445034054414149795443466616532657735624478207460577590891079795564114912418442396707864995938563067755479563850474870766067031326511471051504594777928264027177308453446787478587442663554203039337902473879502917292403539820877956251471612701203572143972352943753791062696757791667318486190154610777475721752749567975013100844032853600120195534259802017090281900264646220781224136443700521419393245058421718455034330177739612895494553069450438317893406027741045575821283411891535713793639123109933196544017309147"}]"""

  "BallotboxApi" should {

    "reject bad auth" in new AppWithDbData() {
      val voteJson = getVote(1, "1")

      val response = route(FakeRequest(POST, routes.BallotboxApi.vote(1, "1").url)
        .withJsonBody(voteJson)
        .withHeaders(("Authorization", "bogus"))
      ).get

      status(response) must equalTo(FORBIDDEN)
    }

    "allow casting votes" in new AppWithDbData() {

      DB.withSession { implicit session =>
        val cfg = TestData.config.validate[ElectionConfig]

        cfg.fold(

          errors => failure(s"Invalid election config json $errors"),

          cfg => {
            Elections.insert(Election(cfg.id, TestData.config.toString, Elections.CREATED, cfg.start_date, cfg.end_date, None, None, None, None, None, None, false, true, None))

            // for validation to work we need to set the pk for the election manually (for election 1020)
            Elections.setPublicKeys(1, pks1020)
            Elections.updateState(1, Elections.CREATED, Elections.STARTED)
          }
        )
      }

      val voteJson = getVote(1, "1")

      val response = route(FakeRequest(POST, routes.BallotboxApi.vote(1, "1").url)
        .withJsonBody(voteJson)
        .withHeaders(("Authorization", getAuth("1", "AuthEvent", 1, "vote")))
      ).get

      status(response) must equalTo(OK)
    }

    "allow dumping votes" in new AppWithDbData() {
      val duration = (100, SECONDS)

      DB.withSession { implicit session =>
        val cfg = TestData.config.validate[ElectionConfig].get
        Elections.insert(Election(cfg.id, TestData.config.toString, Elections.CREATED, cfg.start_date, cfg.end_date, None, None, None, None, None, None, false, false, None))

        // for validation to work we need to set the pk for the election manually (for election 1020)
        Elections.setPublicKeys(1, pks1020)
        Elections.updateState(1, Elections.CREATED, Elections.STARTED)
      }

      val voteJson = getVote(1, "1")

      val response = route(FakeRequest(POST, routes.BallotboxApi.vote(1, "1").url)
        .withJsonBody(voteJson)
        .withHeaders(("Authorization", getAuth("1", "AuthEvent", 1, "vote")))
      ).get

      status(response) must equalTo(OK)

      val voteJson2 = getVote(1, "1")

      val response2 = route(FakeRequest(POST, routes.BallotboxApi.vote(1, "1").url)
        .withJsonBody(voteJson)
        .withHeaders(("Authorization", getAuth("1", "AuthEvent", 1, "vote")))
      ).get

      status(response2) must equalTo(OK)

      Await.result(BallotboxApi.dumpTheVotes(1), duration)

      val path = Datastore.getPath(1, Datastore.CIPHERTEXTS, false)
      java.nio.file.Files.exists(path) must equalTo(true)
      val lines = java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)
      lines.size() must equalTo(1)
    }
  }

  private def getVote(electionId: Long, voterId: String) = {
    // this vote was cast with the 1020 election public keys, to test it that must be the public key for the election
    val voteJson1020 = """{\"a\":\"encrypted-vote-v1\",\"choices\":[{\"alpha\":\"14268858776014931861458608313517070856036869918360554840257322856847542098845060563400252725408151504443689640611782069657557366076314034005926105640102753331283323683927731162586702452504414900483664232251706611593914945053069881853527205681629143320755824446175436453353447242777732548179187099820852306835018099740959374580596232340402230321382898301680181371244175479546234410117758889420419144117988991505324396563056696740570337369435889659133616195621252044920649614410101433256045437156662503299740904855635301136565548458580080122151343981193276667226335535758222123736164713319848692497837135317887590138216\",\"beta\":\"38129594905003909428559756787332613209800236112310345572458923168954311142426936463650020142811211367084243966765625305747152516201815205177928330617455237925660042831501439791160514162960248502061819095120746511150550371639863832578619685629121310674681673529648420418990628021761930770360516061129168052282673912203341582536041559384808650525048379036138240164375027301242348857687731117362577312375465367635984899278073552402864365569327443019073777602998458278982850323707111572658875136980943932951894871021137197698127874020371871548417036437233337397307383410152824383312669339892381058200681549884383129076542\"},{\"alpha\":\"29088883407482755631288867451785451145459495255834955876684619940876261866861340760858999039725793199905550736759985187515700467516831483521228814846648014792319339262966353721343458878923433601722023484967196736194772469411074500118618982321359719709087486677622562863624999853671283011342607539896094542966932006061927627229605414858487537314056224838699869740764547451782009309792152746470801601684065949481663009045824193830885321394443342165833468030820111626294257155562482713680587483972107747252941296163989153297751322456896284112940281013805173575569284220171249693183415381802710531603431441907039352428369\",\"beta\":\"25042815179618695804399500002996234931718703482174947163414199948094060354500739833473616751056318506697092943217039728639138855699136552221326440903435195552216050345002526004798097718966820003230130947417050070804262074732752078635123777039206371717171459023954179025763489859562265617250812641462941987431342878817086863319578864736091888605988579385216823599107029505389018275001418743060575057300470125877277971902121326888281683828672625546368348247467659441308860507497461312776066885011121445786913263909418699600048446540716846253389171367417854800831263585657410276737319332621050547020338863200099578606746\"},{\"alpha\":\"42644078246091347979735007022092136209266755972289295373847945355518063844794508247770301997311829803509491858959377290169416392933094695548277113982884810428692059003116484694088218651136681186364053257244508718686243890519274586485631097549467093086950436380220601170752603226409361311048915703911795506264605977756256960878477868559494037910571335091899861153023199553427077926815404735247696137765369774671022754223423751623939898282367893251661902903677815617891453340665519344393043081071965628116132951831972693750661288402737601333825816326430296687734033301458294444940914762156098396624096342388696403344169\",\"beta\":\"22653906121605281680685760322955912606342662560984462263105041951425368044384939563151441494324947953799361851688977731859585342952119023229195497416190950995909588068730932325137834072827152780506611971748001817559206644197698334584637312087753539461866414196771558237450764346145602329467524230159939566273604433592877530730940565056696934802987230354295291250712268352877639102818750186211679884915308518536418930340514484925808176090878163070755692202872961792817621486311374829949543333393943651376967739981859430459324679676416565990898155555954328276472556106626110239332707870259452352079504803770887074936359\"}],\"election_hash\":{\"a\":\"hash/sha256/value\",\"value\":\"\"},\"issue_date\":\"07/11/2014\",\"proofs\":[{\"challenge\":\"78001874093177159878093429504677748265743675106050781009101214808751744194883\",\"commitment\":\"19652325283417503212671394818084512364934039533193418160291185881640904729960917251875609186180633223191699485617506633218340019307742638726813206831220680708706253181945758931246817528832109616823058863695821341403412079244648960119342394320582881967191753762707975584805412395779487298045659163532558707077910201175721015792735149755455334433193370261714854706337622626605556913606021049650386574179482314401538549467702618760674254714829118975096426476928550691643757710807027349139785537307650181292863594132233848344194239452567352158916153934382874884399565736296461258440047483016141905564470609072527143068713\",\"response\":\"21709072417461931213953125316083694569255580351730652272998161309447698958876455601510820014923788994595219734250012459467528903588282529983131497289589870427004323150900845570710717578396594679196774114879934466887256147250456426197439728672159190620865504151769016906037439881699115129352174076600569736874206872942624876040481481104268987982038803475993731396703304944752509713002743701273201776476218413414649753658462837219505197844070987053775156984772157409602740115589049815593036724839811157510090374041020170578965777896378139929167009040565688888394695581600800734625846020646142264154743248773398850854327\"},{\"challenge\":\"52222863677320195509390108700954977702434493036788529471075256163916521317814\",\"commitment\":\"33409540474052906481284133472609676476184073506430290882051409016054952537098326592212657103493191167077907920287284757647414504416035218756956734267032847352200808722558657679290262608900393210370883519609075883079285988757278171284628924157833417597771347357651978932725560228239143981778188542977527938456247329448863732054816522977574886657373465874232910961563440519358017199330472216689546359142853017423024109251819405274786090602237346922946688678685565995497310428982822969575595145357915411697423489496880686383159064099364805813664486620333295368532055435782075438329698831639806192413682186598766171465258\",\"response\":\"19309735475877960945218363334252769260169120764531180612074712842957575209453606497928006665014028876503895892477468248455071694089176354094535485023564517923491041192889792789578916835101176257831094725537927822442181990575670937751769567017418969571931558759340621390692980223451180060010345126613210221647413725583499918253965351164731092958780285551115238265077083890342173885540198666674833001596721060506840931372804320143927733680856962715280307846716783905812674419874253277398533196722664214491510129158030985287669031868595351800855122812564668396670904624064865372170428700006599235536986299458575067504628\"},{\"challenge\":\"98612167410839376521210184409909984243962447292704239645546358359573426030292\",\"commitment\":\"41535988498679642176066812876417205373099865837917639390808483452010715668100835685806886348340086671454356264434376320916681242642175147018694416916543077039565905282427585863750545942908460262115171230343193432079245184821945805886998530161012361806045673862624824148394598215132603614334049231840703209742946542551518383978325315334921474327740409257647272143890479463352444669118349427307674670696441713399429237489664225058065323821185987413324354985100620302286475509793852259454915844156384966418477543311802937353389624043466865308891450688699030498903950598869492000510563282782204957773647081055599486048860\",\"response\":\"16674479995399418690845146487238515856893342614881736176308307212192388121380774827387230698374169053906553517846241330514230809966240808240083497717785882677472268225134856807096993392561052245125132704318318700911064776967848033910233890199778435208602974475572352297631654646177052215262113317082062371784386004448305257352076843537364556139740783127423851745444946040662794320592529073620551153678172522239378620512317668777473709467991392008553653577576223683531084689606010810110826962939986775580258467915862798447498836913801107495768024147381041889267743834925035028416298503620592844100211529297717528671856\"}]}"""

    val hash = Crypto.sha256(voteJson1020.replace("""\"""", """""""))
    val created = "2015-12-09T18:17:14.457"

    val newVoteJson = s"""{
        "vote": "$voteJson1020",
        "vote_hash": "$hash"
    }"""

    Json.parse(newVoteJson)
  }
}
