/*
 * Copyright (c) Kacper Ziubryniewicz 2019-11-13
 */

package pl.szczodrzynski.edziennik.data.api.edziennik.vulcan.data.api

import androidx.core.util.set
import pl.szczodrzynski.edziennik.*
import pl.szczodrzynski.edziennik.data.api.Regexes.VULCAN_SHIFT_ANNOTATION
import pl.szczodrzynski.edziennik.data.api.VULCAN_API_ENDPOINT_TIMETABLE
import pl.szczodrzynski.edziennik.data.api.edziennik.vulcan.DataVulcan
import pl.szczodrzynski.edziennik.data.api.edziennik.vulcan.ENDPOINT_VULCAN_API_TIMETABLE
import pl.szczodrzynski.edziennik.data.api.edziennik.vulcan.data.VulcanApi
import pl.szczodrzynski.edziennik.data.api.models.DataRemoveModel
import pl.szczodrzynski.edziennik.data.db.entity.*
import pl.szczodrzynski.edziennik.utils.Utils.crc16
import pl.szczodrzynski.edziennik.utils.Utils.d
import pl.szczodrzynski.edziennik.utils.models.Date
import pl.szczodrzynski.edziennik.utils.models.Week

class VulcanApiTimetable(override val data: DataVulcan,
                         override val lastSync: Long?,
                         val onSuccess: (endpointId: Int) -> Unit
) : VulcanApi(data, lastSync) {
    companion object {
        const val TAG = "VulcanApiTimetable"
    }

    init { data.profile?.also { profile ->
        val currentWeekStart = Week.getWeekStart()

        if (Date.getToday().weekDay > 4) {
            currentWeekStart.stepForward(0, 0, 7)
        }

        val getDate = data.arguments?.getString("weekStart") ?: currentWeekStart.stringY_m_d

        val weekStart = Date.fromY_m_d(getDate)
        val weekEnd = weekStart.clone().stepForward(0, 0, 6)

        apiGet(TAG, VULCAN_API_ENDPOINT_TIMETABLE, parameters = mapOf(
                "DataPoczatkowa" to weekStart.stringY_m_d,
                "DataKoncowa" to weekEnd.stringY_m_d,
                "IdUczen" to data.studentId,
                "IdOddzial" to data.studentClassId,
                "IdOkresKlasyfikacyjny" to data.studentSemesterId
        )) { json, _ ->
            val dates = mutableSetOf<Int>()
            val lessons = mutableListOf<Lesson>()

            json.getJsonArray("Data")?.asJsonObjectList()?.forEach { lesson ->
                if (lesson.getBoolean("PlanUcznia") != true)
                    return@forEach
                val lessonDate = Date.fromY_m_d(lesson.getString("DzienTekst"))
                val lessonNumber = lesson.getInt("NumerLekcji")
                val lessonRange = data.lessonRanges.singleOrNull { it.lessonNumber == lessonNumber }
                val startTime = lessonRange?.startTime
                val endTime = lessonRange?.endTime
                val teacherId = lesson.getLong("IdPracownik")
                val classroom = lesson.getString("Sala")

                val oldTeacherId = lesson.getLong("IdPracownikOld")

                val changeAnnotation = lesson.getString("AdnotacjaOZmianie") ?: ""
                val type = when {
                    changeAnnotation.startsWith("(przeniesiona z") -> Lesson.TYPE_SHIFTED_TARGET
                    changeAnnotation.startsWith("(przeniesiona na") -> Lesson.TYPE_SHIFTED_SOURCE
                    changeAnnotation.startsWith("(zastępstwo") -> Lesson.TYPE_CHANGE
                    lesson.getBoolean("PrzekreslonaNazwa") == true -> Lesson.TYPE_CANCELLED
                    else -> Lesson.TYPE_NORMAL
                }

                val teamId = lesson.getString("PodzialSkrot")?.let { teamName ->
                    val name = "${data.teamClass?.name} $teamName"
                    val id = name.crc16().toLong()
                    var team = data.teamList.singleOrNull { it.name == name }
                    if (team == null) {
                        team = Team(
                                profileId,
                                id,
                                name,
                                Team.TYPE_VIRTUAL,
                                "${data.schoolName}:$name",
                                teacherId ?: oldTeacherId ?: -1
                        )
                        data.teamList[id] = team
                    }
                    team.id
                } ?: data.teamClass?.id ?: -1

                val subjectId = lesson.getLong("IdPrzedmiot").let { id ->
                    // get the specified subject name
                    val subjectName = lesson.getString("PrzedmiotNazwa") ?: ""

                    val condition = when (id) {
                        // "special" subject - e.g. one time classes, a trip, etc.
                        0L -> { subject: Subject -> subject.longName == subjectName }
                        // normal subject, check if it exists
                        else -> { subject: Subject -> subject.id == id }
                    }

                    data.subjectList.singleOrNull(condition)?.id ?: {
                        /**
                         * CREATE A NEW SUBJECT IF IT DOESN'T EXIST
                         */

                        val subjectObject = Subject(
                                profileId,
                                if (id == null || id == 0L)
                                    -1 * crc16(subjectName.toByteArray()).toLong()
                                else
                                    id,
                                subjectName,
                                subjectName
                        )
                        data.subjectList.put(subjectObject.id, subjectObject)
                        subjectObject.id
                    }()
                }

                val lessonObject = Lesson(profileId, -1).apply {
                    this.type = type

                    when (type) {
                        Lesson.TYPE_NORMAL, Lesson.TYPE_CHANGE, Lesson.TYPE_SHIFTED_TARGET -> {
                            this.date = lessonDate
                            this.lessonNumber = lessonNumber
                            this.startTime = startTime
                            this.endTime = endTime
                            this.subjectId = subjectId
                            this.teacherId = teacherId
                            this.teamId = teamId
                            this.classroom = classroom

                            this.oldTeacherId = oldTeacherId
                        }

                        Lesson.TYPE_CANCELLED, Lesson.TYPE_SHIFTED_SOURCE -> {
                            this.oldDate = lessonDate
                            this.oldLessonNumber = lessonNumber
                            this.oldStartTime = startTime
                            this.oldEndTime = endTime
                            this.oldSubjectId = subjectId
                            this.oldTeacherId = teacherId
                            this.oldTeamId = teamId
                            this.oldClassroom = classroom
                        }
                    }

                    if (type == Lesson.TYPE_SHIFTED_SOURCE || type == Lesson.TYPE_SHIFTED_TARGET) {
                        val shift = VULCAN_SHIFT_ANNOTATION.find(changeAnnotation)
                        val oldLessonNumber = shift?.get(2)?.toInt()
                        val oldLessonDate = shift?.get(3)?.let { Date.fromd_m_Y(it) }

                        val oldLessonRange = data.lessonRanges.singleOrNull { it.lessonNumber == oldLessonNumber }
                        val oldStartTime = oldLessonRange?.startTime
                        val oldEndTime = oldLessonRange?.endTime

                        when (type) {
                            Lesson.TYPE_SHIFTED_SOURCE -> {
                                this.lessonNumber = oldLessonNumber
                                this.date = oldLessonDate
                                this.startTime = oldStartTime
                                this.endTime = oldEndTime
                            }

                            Lesson.TYPE_SHIFTED_TARGET -> {
                                this.oldLessonNumber = oldLessonNumber
                                this.oldDate = oldLessonDate
                                this.oldStartTime = oldStartTime
                                this.oldEndTime = oldEndTime
                            }
                        }
                    }

                    this.id = buildId()
                }

                val seen = profile.empty || lessonDate < Date.getToday()

                if (type != Lesson.TYPE_NORMAL) {
                    data.metadataList.add(Metadata(
                            profileId,
                            Metadata.TYPE_LESSON_CHANGE,
                            lessonObject.id,
                            seen,
                            seen,
                            System.currentTimeMillis()
                    ))
                }

                dates.add(lessonDate.value)
                lessons.add(lessonObject)
            }

            val date: Date = weekStart.clone()
            while (date <= weekEnd) {
                if (!dates.contains(date.value)) {
                    lessons.add(Lesson(profileId, date.value.toLong()).apply {
                        this.type = Lesson.TYPE_NO_LESSONS
                        this.date = date.clone()
                    })
                }

                date.stepForward(0, 0, 1)
            }

            d(TAG, "Clearing lessons between ${weekStart.stringY_m_d} and ${weekEnd.stringY_m_d} - timetable downloaded for $getDate")

            data.lessonList.addAll(lessons)
            data.toRemove.add(DataRemoveModel.Timetable.between(weekStart, weekEnd))

            data.setSyncNext(ENDPOINT_VULCAN_API_TIMETABLE, SYNC_ALWAYS)
            onSuccess(ENDPOINT_VULCAN_API_TIMETABLE)
        }
    }}
}
