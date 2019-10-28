/*
 * Copyright (c) Kuba Szczodrzyński 2019-10-28.
 */

package pl.szczodrzynski.edziennik.api.v2.idziennik.data.web

import com.google.gson.JsonObject
import pl.szczodrzynski.edziennik.api.v2.ERROR_IDZIENNIK_WEB_REQUEST_NO_DATA
import pl.szczodrzynski.edziennik.api.v2.IDZIENNIK_WEB_EXAMS
import pl.szczodrzynski.edziennik.api.v2.idziennik.DataIdziennik
import pl.szczodrzynski.edziennik.api.v2.idziennik.ENDPOINT_IDZIENNIK_WEB_EXAMS
import pl.szczodrzynski.edziennik.api.v2.idziennik.data.IdziennikWeb
import pl.szczodrzynski.edziennik.api.v2.models.ApiError
import pl.szczodrzynski.edziennik.data.db.modules.api.SYNC_ALWAYS
import pl.szczodrzynski.edziennik.data.db.modules.events.Event
import pl.szczodrzynski.edziennik.data.db.modules.lessons.Lesson
import pl.szczodrzynski.edziennik.data.db.modules.metadata.Metadata
import pl.szczodrzynski.edziennik.getJsonObject
import pl.szczodrzynski.edziennik.utils.models.Date

class IdziennikWebExams(override val data: DataIdziennik,
                         val onSuccess: () -> Unit) : IdziennikWeb(data) {
    companion object {
        private const val TAG = "IdziennikWebExams"
    }

    init {
        getExams()
    }

    private var examsYear = Date.getToday().year
    private var examsMonth = Date.getToday().month
    private var examsMonthsChecked = 0
    private var examsNextMonthChecked = false // TO DO temporary // no more // idk
    private fun getExams() {
        val param = JsonObject()
        param.addProperty("strona", 1)
        param.addProperty("iloscNaStrone", "99")
        param.addProperty("iloscRekordow", -1)
        param.addProperty("kolumnaSort", "ss.Nazwa,sp.Data_sprawdzianu")
        param.addProperty("kierunekSort", 0)
        param.addProperty("maxIloscZaznaczonych", 0)
        param.addProperty("panelFiltrow", 0)

        webApiGet(TAG, IDZIENNIK_WEB_EXAMS, mapOf(
                "idP" to data.registerId,
                "rok" to examsYear,
                "miesiac" to examsMonth,
                "param" to param
        )) { result ->
            val json = result.getJsonObject("d") ?: run {
                data.error(ApiError(TAG, ERROR_IDZIENNIK_WEB_REQUEST_NO_DATA)
                        .withApiResponse(result))
                return@webApiGet
            }

            for (jExamEl in json.getAsJsonArray("ListK")) {
                val jExam = jExamEl.asJsonObject
                // jExam
                val eventId = jExam.get("_recordId").asLong
                val rSubject = data.getSubject(jExam.get("przedmiot").asString, -1, "")
                val rTeacher = data.getTeacherByLastFirst(jExam.get("wpisal").asString)
                val examDate = Date.fromY_m_d(jExam.get("data").asString)
                val lessonObject = Lesson.getByWeekDayAndSubject(data.lessonList, examDate.weekDay, rSubject.id)
                val examTime = lessonObject?.startTime

                val eventType = if (jExam.get("rodzaj").asString == "sprawdzian/praca klasowa") Event.TYPE_EXAM else Event.TYPE_SHORT_QUIZ
                val eventObject = Event(
                        profileId,
                        eventId,
                        examDate,
                        examTime,
                        jExam.get("zakres").asString,
                        -1,
                        eventType,
                        false,
                        rTeacher.id,
                        rSubject.id,
                        data.teamClass?.id ?: -1
                )

                data.eventList.add(eventObject)
                data.metadataList.add(Metadata(
                        profileId,
                        Metadata.TYPE_EVENT,
                        eventObject.id,
                        profile?.empty ?: false,
                        profile?.empty ?: false,
                        System.currentTimeMillis()
                ))
            }

            if (profile?.empty == true && examsMonthsChecked < 3 /* how many months backwards to check? */) {
                examsMonthsChecked++
                examsMonth--
                if (examsMonth < 1) {
                    examsMonth = 12
                    examsYear--
                }
                getExams()
            } else if (!examsNextMonthChecked /* get also one month forward */) {
                val showDate = Date.getToday().stepForward(0, 1, 0)
                examsYear = showDate.year
                examsMonth = showDate.month
                examsNextMonthChecked = true
                getExams()
            } else {
                data.setSyncNext(ENDPOINT_IDZIENNIK_WEB_EXAMS, SYNC_ALWAYS)
                onSuccess()
            }
        }
    }
}