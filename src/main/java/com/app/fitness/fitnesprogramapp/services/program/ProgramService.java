package com.app.fitness.fitnesprogramapp.services.program;

import com.app.fitness.fitnesprogramapp.dtos.program.*;
import com.app.fitness.fitnesprogramapp.dtos.program.details.*;
import com.app.fitness.fitnesprogramapp.dtos.program.startprogram.StartProgramResponseDTO;
import com.app.fitness.fitnesprogramapp.dtos.program.update.*;
import com.app.fitness.fitnesprogramapp.dtos.user.UserProgramDetailsDTO;
import com.app.fitness.fitnesprogramapp.exceptions.customExceptions.ProgramNotFoundException;
import com.app.fitness.fitnesprogramapp.models.exercise.Exercise;
import com.app.fitness.fitnesprogramapp.models.exercise.WorkoutExercise;
import com.app.fitness.fitnesprogramapp.models.program.*;
import com.app.fitness.fitnesprogramapp.models.set.*;
import com.app.fitness.fitnesprogramapp.models.set.Set;
import com.app.fitness.fitnesprogramapp.models.user.User;
import com.app.fitness.fitnesprogramapp.models.week.Week;
import com.app.fitness.fitnesprogramapp.models.workout.StartedWorkout;
import com.app.fitness.fitnesprogramapp.models.workout.Workout;
import com.app.fitness.fitnesprogramapp.repositories.exercise.ExerciseRepository;
import com.app.fitness.fitnesprogramapp.repositories.exercise.WorkoutExerciseRepository;
import com.app.fitness.fitnesprogramapp.repositories.metrics.IntensityMetricRepository;
import com.app.fitness.fitnesprogramapp.repositories.metrics.VolumeMetricRepository;
import com.app.fitness.fitnesprogramapp.repositories.program.*;
import com.app.fitness.fitnesprogramapp.repositories.set.SetRepository;
import com.app.fitness.fitnesprogramapp.repositories.startedprogram.StartedProgramRepository;
import com.app.fitness.fitnesprogramapp.repositories.user.UserRepository;
import com.app.fitness.fitnesprogramapp.repositories.week.WeekRepository;
import com.app.fitness.fitnesprogramapp.repositories.workout.WorkoutRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProgramService {
    private final ProgramRepository programRepository;
    private final WeekRepository weekRepository;
    private final WorkoutRepository workoutRepository;
    private final WorkoutExerciseRepository workoutExerciseRepository;
    private final SetRepository setRepository;
    private final ExerciseRepository exerciseRepository;
    private final UserRepository userRepository;
    private final VolumeMetricRepository volumeMetricRepository;
    private final IntensityMetricRepository intensityMetricRepository;
    private final ObjectMapper objectMapper;
    private final StartedProgramRepository startedProgramRepository;



    public Page<ProgramOverviewDTO> getAllProgramsOverview(Pageable pageable) {
        Page<Program> programPage = programRepository.findAll(pageable);
        return programPage.map(ProgramOverviewDTO::fromEntity);
    }

    public Page<ProgramOverviewDTO> searchProgramsByTitle(String title, Pageable pageable) {
        Page<Program> programPage = programRepository.searchByTitle("%"+title+"%", pageable);
        return programPage.map(ProgramOverviewDTO::fromEntity);
    }

    public Page<ProgramOverviewDTO> getStartedProgramsOverview(Pageable pageable, String username) {
        User user = userRepository.findByUsername(username).orElseThrow();

        List<ProgramOverviewDTO> programDTOs = user.getStartedPrograms().stream()
                .sorted((sp1, sp2) -> sp2.getStartDate().compareTo(sp1.getStartDate())) // Sort by started date - newest first
                .map(startedProgram -> {
                    Program program = programRepository.findById(startedProgram.getProgramId()).orElse(null);

                    return ProgramOverviewDTO.fromEntity(program, startedProgram.getId(), startedProgram.isFinished());
                })
                .toList();

        return new PageImpl<>(programDTOs, pageable, programDTOs.size());
    }

    public ProgramCreateDTO convertJsonToDTO(String programJson) {
        try {
            return objectMapper.readValue(programJson, ProgramCreateDTO.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public ProgramUpdateDTO convertJsonToUpdateDTO(String programJson) {
        try {
            return objectMapper.readValue(programJson, ProgramUpdateDTO.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public Program createProgram(ProgramCreateDTO programDTO, MultipartFile image, String username) {
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Map<Long, VolumeMetric> volumeMetricsMap = prefetchVolumeMetrics(programDTO);
        Map<Long, IntensityMetric> intensityMetricsMap = prefetchIntensityMetrics(programDTO);
        Map<Long, Exercise> exerciseMap = prefetchExercises(programDTO);

        // 2. Build the main program entity
        Program program = new Program();
        program.setTitle(programDTO.getName());
        program.setDescription(programDTO.getDescription());
        program.setPublic(programDTO.isPublic());
        program.setCreator(currentUser);
        program.setFollowersNumber(0);

        // Process image if provided
        if (image != null && !image.isEmpty()) {
            try {
                program.setImageData(image.getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 3. Build the entire object graph IN MEMORY
        List<Week> weeks = new ArrayList<>();
        int weekPosition = 0;
        for (WeekDTO weekDTO : programDTO.getWeeks()) {
            Week week = new Week(); // Don't save yet!
            week.setPosition(weekPosition++);
            week.setProgram(program);
            List<Workout> workouts = new ArrayList<>();
            int workoutPos = 0;
            for (WorkoutDTO workoutDTO : weekDTO.getWorkouts()) {
                Workout workout = new Workout(); // Don't save yet!
                workout.setTitle(workoutDTO.getTitle());
                workout.setDescription(workoutDTO.getDescription());
                workout.setPosition(workoutPos++);
                workout.setWeek(week);

                List<WorkoutExercise> workoutExercises = new ArrayList<>();
                int exercisePos = 0;
                for (WorkoutExerciseDTO exerciseDTO : workoutDTO.getWorkoutExercises()) {
                    WorkoutExercise workoutExercise = new WorkoutExercise(); // Don't save yet!

                    Exercise exercise = exerciseMap.get(exerciseDTO.getExercise());
                    workoutExercise.setExercise(exercise);
                    workoutExercise.setWorkout(workout);
                    workoutExercise.setMinimumRestTime(exerciseDTO.getMinimumRestTime());
                    workoutExercise.setMaximumRestTime(exerciseDTO.getMaximumRestTime());
                    workoutExercise.setPosition(exercisePos++);

                    List<Set> sets = new ArrayList<>();
                    for (CreateSetDTO setDTO : exerciseDTO.getSets()) {
                        Set set = new Set();
                        set.setWorkoutExercise(workoutExercise);
                        SetVolume volume = new SetVolume(setDTO.getVolumeMin(), setDTO.getVolumeMax());
                        SetIntensity intensity = new SetIntensity(setDTO.getIntensityMin(), setDTO.getIntensityMax());
                        set.setVolume(volume);
                        set.setIntensity(intensity);

                        set.setVolumeMetric(volumeMetricsMap.get(setDTO.getVolumeMetric()));
                        set.setIntensityMetric(intensityMetricsMap.get(setDTO.getIntensityMetric()));

                        sets.add(set);
                    }
                    workoutExercise.setSets(sets);
                    workoutExercises.add(workoutExercise);
                }
                workout.setWorkoutExercises(workoutExercises);
                workouts.add(workout);
            }
            week.setWorkouts(workouts);
            weeks.add(week);
        }
        program.setWeeks(weeks);

        return programRepository.save(program);
    }

    public byte[] getProgramImage(Long programId) {
        Program program = programRepository.findById(programId).orElse(null);
        if (program == null) {
            return null;
        }
        return program.getImageData();
    }

    public ProgramDetailsDTO getProgramDetails(Long id, String username) {
        Program program = programRepository.findAndInitializeById(id)
                .orElseThrow(() -> new ProgramNotFoundException("Program not found"));
        return mapProgramToDetailsDTO(program, username);
    }

    private ProgramDetailsDTO mapProgramToDetailsDTO(Program program, String username) {
        ProgramDetailsDTO programDetailsDTO = new ProgramDetailsDTO();
        programDetailsDTO.setId(program.getId());
        programDetailsDTO.setName(program.getTitle());
        programDetailsDTO.setDescription(program.getDescription());
        programDetailsDTO.setPublic(program.isPublic());
        programDetailsDTO.setFollowersNumber(program.getFollowersNumber());
        programDetailsDTO.setRating(program.getRating());
        programDetailsDTO.setCreatedByUser(username.equals(program.getCreator().getUsername()));
        // Map creator
        programDetailsDTO.setCreator(mapUserToProgramDetailsDTO(program.getCreator()));

        // Map weeks with their workouts
        programDetailsDTO.setWeeks(mapWeeksToDetailsDTOs(program.getWeeks()));

        return programDetailsDTO;
    }

    private UserProgramDetailsDTO mapUserToProgramDetailsDTO(User user) {
        UserProgramDetailsDTO creatorDTO = new UserProgramDetailsDTO();
        creatorDTO.setId(user.getId());
        creatorDTO.setUsername(user.getUsername());
        // Add other user fields as needed
        return creatorDTO;
    }

    public List<WeekDetailsDTO> mapWeeksToDetailsDTOs(List<Week> weeks) {
        return weeks.stream()
                .map(this::mapWeekToDetailsDTO)
                .toList();
    }

    public WeekDetailsDTO mapWeekToDetailsDTO(Week week) {
        WeekDetailsDTO weekDetailsDTO = new WeekDetailsDTO();
        weekDetailsDTO.setId(week.getId());
        weekDetailsDTO.setWorkouts(mapWorkoutsToDetailsDTOs(week.getWorkouts()));
        return weekDetailsDTO;
    }

    public List<WorkoutDetailsDTO> mapWorkoutsToDetailsDTOs(List<Workout> workouts) {
        return workouts.stream()
                .sorted(Comparator.comparing(Workout::getPosition))
                .map(this::mapWorkoutToDetailsDTO)
                .toList();
    }

    public WorkoutDetailsDTO mapWorkoutToDetailsDTO(Workout workout) {
        WorkoutDetailsDTO workoutDetailsDTO = new WorkoutDetailsDTO();
        workoutDetailsDTO.setId(workout.getId());
        workoutDetailsDTO.setTitle(workout.getTitle());
        workoutDetailsDTO.setDescription(workout.getDescription());
        workoutDetailsDTO.setWorkoutExercises(mapWorkoutExercisesToDetailsDTOs(workout.getWorkoutExercises()));
        return workoutDetailsDTO;
    }

    public List<WorkoutExerciseDetailsDTO> mapWorkoutExercisesToDetailsDTOs(List<WorkoutExercise> exercises) {
        return exercises.stream()
                .map(this::mapWorkoutExerciseToDetailsDTO)
                .toList();
    }

    public WorkoutExerciseDetailsDTO mapWorkoutExerciseToDetailsDTO(WorkoutExercise exercise) {
        WorkoutExerciseDetailsDTO exerciseDetailsDTO = new WorkoutExerciseDetailsDTO();
        exerciseDetailsDTO.setId(exercise.getId());
        exerciseDetailsDTO.setExercise(mapExerciseToDetailsDTO(exercise.getExercise()));
        exerciseDetailsDTO.setMinimumRestTime(exercise.getMinimumRestTime());
        exerciseDetailsDTO.setMaximumRestTime(exercise.getMaximumRestTime());
        exerciseDetailsDTO.setSets(mapSetsToDetailsDTOs(exercise.getSets()));
        return exerciseDetailsDTO;
    }

    public ExerciseDetailsDTO mapExerciseToDetailsDTO(Exercise exercise) {
        ExerciseDetailsDTO exerciseDetailsDTO = new ExerciseDetailsDTO();
        exerciseDetailsDTO.setId(exercise.getId());
        exerciseDetailsDTO.setTitle(exercise.getTitle());
        exerciseDetailsDTO.setDescription(exercise.getDescription());
        exerciseDetailsDTO.setLink(exercise.getLink());
        return exerciseDetailsDTO;
    }

    public List<SetDetailsDTO> mapSetsToDetailsDTOs(List<Set> sets) {
        return sets.stream()
                .map(this::mapSetToDetailsDTO)
                .toList();
    }

    public SetDetailsDTO mapSetToDetailsDTO(Set set) {
        SetDetailsDTO setDetailsDTO = new SetDetailsDTO();
        setDetailsDTO.setId(set.getId());

        // Map volume
        SetVolumeDetailsDTO volumeDTO = new SetVolumeDetailsDTO(
                set.getVolume().getMinimumVolume(),
                set.getVolume().getMaximumVolume()
        );
        setDetailsDTO.setVolume(volumeDTO);

        // Map intensity
        SetIntensityDetailsDTO intensityDTO = new SetIntensityDetailsDTO(
                set.getIntensity().getMinimumIntensity(),
                set.getIntensity().getMaximumIntensity()
        );
        setDetailsDTO.setIntensity(intensityDTO);

        // Map volume metric
        setDetailsDTO.setVolumeMetric(mapVolumeMetricToDetailsDTO(set.getVolumeMetric()));

        // Map intensity metric
        setDetailsDTO.setIntensityMetric(mapIntensityMetricToDetailsDTO(set.getIntensityMetric()));

        return setDetailsDTO;
    }

    public VolumeMetricDetailsDTO mapVolumeMetricToDetailsDTO(VolumeMetric volumeMetric) {
        VolumeMetricDetailsDTO volumeMetricDTO = new VolumeMetricDetailsDTO();
        volumeMetricDTO.setId(volumeMetric.getId());
        volumeMetricDTO.setRange(volumeMetric.isRange());
        volumeMetricDTO.setTitle(volumeMetric.getTitle());
        volumeMetricDTO.setMetricSymbol(volumeMetric.getMetricSymbol());
        return volumeMetricDTO;
    }

    public IntensityMetricDetailsDTO mapIntensityMetricToDetailsDTO(IntensityMetric intensityMetric) {
        IntensityMetricDetailsDTO intensityMetricDTO = new IntensityMetricDetailsDTO();
        intensityMetricDTO.setId(intensityMetric.getId());
        intensityMetricDTO.setMinimumIntensity(intensityMetric.getMinimumIntensity());
        intensityMetricDTO.setMaximumIntensity(intensityMetric.getMaximumIntensity());
        intensityMetricDTO.setRange(intensityMetric.isRange());
        intensityMetricDTO.setTitle(intensityMetric.getTitle());
        intensityMetricDTO.setMetricSymbol(intensityMetric.getMetricSymbol());
        return intensityMetricDTO;
    }



    public Set createWorkoutExerciseSet(
            CreateSetDTO setDTO) {

        Set set = new Set();

        // Get the metric entities by ID
        VolumeMetric volumeMetric = volumeMetricRepository.findById(setDTO.getVolumeMetric())
                .orElseThrow(() -> new RuntimeException("Volume metric not found with ID: " + setDTO.getVolumeMetric()));

        IntensityMetric intensityMetric = intensityMetricRepository.findById(setDTO.getIntensityMetric())
                .orElseThrow(() -> new RuntimeException("Intensity metric not found with ID: " + setDTO.getIntensityMetric()));

        // Set the metric references
        set.setVolumeMetric(volumeMetric);
        set.setIntensityMetric(intensityMetric);

        // Create and set embedded volume
        SetVolume volume = new SetVolume();
        volume.setMinimumVolume(setDTO.getVolumeMin());
        volume.setMaximumVolume(setDTO.getVolumeMax());
        set.setVolume(volume);

        // Create and set embedded intensity
        SetIntensity intensity = new SetIntensity();
        intensity.setMinimumIntensity(setDTO.getIntensityMin());
        intensity.setMaximumIntensity(setDTO.getIntensityMax());
        set.setIntensity(intensity);

        return setRepository.save(set);
    }


    public StartProgramResponseDTO startProgram(Long programId, String username) {
        Program program = programRepository.findById(programId).orElseThrow(() -> new RuntimeException("Program not found!"));
        User user = userRepository.findByUsername(username).orElseThrow(() -> new RuntimeException("User not found!"));
        StartedProgram startedProgram = new StartedProgram();
        startedProgram.setProgramId(program.getId());
        startedProgram.setStartDate(new Date());
        startedProgramRepository.save(startedProgram);
        user.getStartedPrograms().add(startedProgram);
        userRepository.save(user);
        return new StartProgramResponseDTO(startedProgram.getId(), program.getTitle());

    }


    @Transactional
    public Program updateProgram(Long programId, ProgramUpdateDTO programDTO, MultipartFile image, String username) {
        // --- Standard Checks ---
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // We only need the top-level program ID. No need to fetch the graph.
        if (!programRepository.existsById(programId)) {
            throw new ProgramNotFoundException("Program not found with ID: " + programId);
        }
        User creator = programRepository.findCreatorById(programId); // A simple query to get the creator for the check
        if (!creator.getId().equals(currentUser.getId())) {
            throw new RuntimeException("You don't have permission to edit this program");
        }

        // --- STEP 1: FAST, STATELESS BULK DELETE (Bottom-Up) ---
        // These queries bypass Hibernate's cache and are very fast.
        setRepository.bulkDeleteByProgramId(programId);
        workoutExerciseRepository.bulkDeleteByProgramId(programId);


        // --- STEP 3: RE-FETCH THE PARENT ENTITY & UPDATE ---
        // Now we get a clean, fresh Program object that knows it has no children.
        Program program = programRepository.findAndInitializeById(programId).get();

        program.setTitle(programDTO.getName());
        program.setPublic(programDTO.isPublic());
        program.setDescription(programDTO.getDescription());

        if (image != null && !image.isEmpty()) {
            try {
                program.setImageData(image.getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            program.setImageData(null);
        }


        // STEP 7: PRE-FETCH DEPENDENCIES FOR THE NEW GRAPH
        Map<Long, Exercise> exerciseMap = prefetchExercises(programDTO);
        Map<Long, VolumeMetric> volumeMetricsMap = prefetchVolumeMetrics(programDTO);
        Map<Long, IntensityMetric> intensityMetricsMap = prefetchIntensityMetrics(programDTO);

        // STEP 8: REBUILD THE ENTIRE GRAPH

        updateProgramWeeks(program,programDTO.getWeeks(),exerciseMap,volumeMetricsMap,intensityMetricsMap);

        return program;
    }


    private void updateProgramWeeks(Program program, List<UpdateWeekDTO> weekDTOs,Map<Long,Exercise> exerciseMap,Map<Long,VolumeMetric> volumeMetricsMap,Map<Long,IntensityMetric> intensityMetricsMap) {
        List<Week> existingWeeks = new ArrayList<>(program.getWeeks());
        List<Week> updatedWeeks = new ArrayList<>();
        int weekPosition = 0;

        for (UpdateWeekDTO weekDTO : weekDTOs) {
            Week week = findOrCreateWeek(existingWeeks, weekDTO);
            week.setPosition(weekPosition++);
            if(week.getId()==null){
                week.setProgram(program);
            }
            updateWeekWorkouts(week, weekDTO.getWorkouts(),exerciseMap,volumeMetricsMap,intensityMetricsMap);

            updatedWeeks.add(week);
        }
        program.getWeeks().clear();
        program.getWeeks().addAll(updatedWeeks);

        List<Week> weeksToRemove = existingWeeks.stream()
                .filter(week -> (week!=null && updatedWeeks.stream().filter(uw->uw.getId()!=null)
                        .noneMatch(updatedWeek -> updatedWeek.getId().equals(week.getId()))))
                .toList();

        weekRepository.deleteAllInBatch(weeksToRemove);
    }

    private Week findOrCreateWeek(List<Week> existingWeeks, UpdateWeekDTO weekDTO) {
        if (weekDTO.getId() != null) {
            for (Week existingWeek : existingWeeks) {
                if (existingWeek.getId().equals(weekDTO.getId())) {
                    return existingWeek;
                }
            }
        }
        Week newWeek = new Week();
        newWeek.setWorkouts(new ArrayList<>());
        return newWeek;
    }

    private void updateWeekWorkouts(Week week, List<UpdateWorkoutDTO> workoutDTOs,Map<Long,Exercise> exerciseMap,Map<Long,VolumeMetric> volumeMetricsMap,Map<Long,IntensityMetric> intensityMetricsMap) {
        List<Workout> existingWorkouts = new ArrayList<>(week.getWorkouts());
        List<Workout> updatedWorkouts = new ArrayList<>();

        int i = 0;
        for (UpdateWorkoutDTO workoutDTO : workoutDTOs) {
            Workout workout = findOrCreateWorkout(existingWorkouts, workoutDTO);
            if(workout.getId()==null){
                workout.setWeek(week);
            }
            workout.setTitle(workoutDTO.getTitle());
            workout.setDescription(workoutDTO.getDescription());
            workout.setPosition(i);

            replaceWorkoutExercises(workout, workoutDTO.getWorkoutExercises(),exerciseMap,volumeMetricsMap,intensityMetricsMap);

            updatedWorkouts.add(workout);
            i++;
        }

        week.getWorkouts().clear();
        week.getWorkouts().addAll(updatedWorkouts);

        List<Workout> workoutsToRemove = existingWorkouts.stream()
                .filter(workout -> updatedWorkouts.stream()
                        .noneMatch(updatedWorkout -> updatedWorkout.getId().equals(workout.getId())))
                .toList();

        workoutRepository.deleteAllInBatch(workoutsToRemove);
    }

    private Workout findOrCreateWorkout(List<Workout> existingWorkouts, UpdateWorkoutDTO workoutDTO) {
        if (workoutDTO.getId() != null) {
            for (Workout existingWorkout : existingWorkouts) {
                if (existingWorkout.getId().equals(workoutDTO.getId())) {
                    return existingWorkout;
                }
            }
        }

        Workout newWorkout = new Workout();
        newWorkout.setWorkoutExercises(new ArrayList<>());
        return newWorkout;
    }

    private void replaceWorkoutExercises(Workout workout, List<UpdateWorkoutExerciseDTO> exerciseDTOs,Map<Long,Exercise> exerciseMap,Map<Long,VolumeMetric> volumeMetricsMap,Map<Long,IntensityMetric> intensityMetricsMap) {
        List<WorkoutExercise> newExercises = new ArrayList<>();

        int i = 0;
        for (UpdateWorkoutExerciseDTO exerciseDTO : exerciseDTOs) {
            WorkoutExercise workoutExercise = createNewWorkoutExercise(workout,exerciseDTO, i,exerciseMap,volumeMetricsMap,intensityMetricsMap);
            newExercises.add(workoutExercise);
            i++;
        }

        workout.getWorkoutExercises().clear();
        workout.getWorkoutExercises().addAll(newExercises);

    }

    private WorkoutExercise createNewWorkoutExercise(Workout workout,UpdateWorkoutExerciseDTO exerciseDTO, int position,Map<Long,Exercise> exerciseMap,Map<Long,VolumeMetric> volumeMetricsMap,Map<Long,IntensityMetric> intensityMetricsMap) {
        WorkoutExercise workoutExercise = new WorkoutExercise();

        Exercise exercise = exerciseMap.get(exerciseDTO.getExercise());

        workoutExercise.setExercise(exercise);
        workoutExercise.setMinimumRestTime(exerciseDTO.getMinimumRestTime());
        workoutExercise.setMaximumRestTime(exerciseDTO.getMaximumRestTime());
        workoutExercise.setPosition(position);
        workoutExercise.setWorkout(workout);

        List<Set> sets = new ArrayList<>();
        for (UpdateWorkoutExerciseSetDTO setDTO : exerciseDTO.getSets()) {
            Set set = createNewSet(setDTO,volumeMetricsMap,intensityMetricsMap);
            set.setWorkoutExercise(workoutExercise);
            sets.add(set);
        }
        workoutExercise.setSets(sets);

        return workoutExercise;
    }

    private Set createNewSet(UpdateWorkoutExerciseSetDTO setDTO,Map<Long,VolumeMetric> volumeMetricsMap,Map<Long,IntensityMetric> intensityMetricsMap) {
        Set set = new Set();

        VolumeMetric volumeMetric = volumeMetricsMap.get(setDTO.getVolumeMetric());

        IntensityMetric intensityMetric = intensityMetricsMap.get(setDTO.getIntensityMetric());

        set.setVolumeMetric(volumeMetric);
        set.setIntensityMetric(intensityMetric);

        SetVolume volume = new SetVolume();
        volume.setMinimumVolume(setDTO.getVolumeMin());
        volume.setMaximumVolume(setDTO.getVolumeMax());
        set.setVolume(volume);

        SetIntensity intensity = new SetIntensity();
        intensity.setMinimumIntensity(setDTO.getIntensityMin());
        intensity.setMaximumIntensity(setDTO.getIntensityMax());
        set.setIntensity(intensity);

        return set;
    }




    private Map<Long, Exercise> prefetchExercises(ProgramUpdateDTO programDTO) {
        java.util.Set<Long> ids = programDTO.getWeeks().stream()
                .flatMap(w -> w.getWorkouts().stream())
                .flatMap(wo -> wo.getWorkoutExercises().stream())
                .map(UpdateWorkoutExerciseDTO::getExercise)
                .collect(Collectors.toSet());
        return exerciseRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Exercise::getId, e -> e));
    }

    private Map<Long, VolumeMetric> prefetchVolumeMetrics(ProgramUpdateDTO programDTO) {
        java.util.Set<Long> ids = programDTO.getWeeks().stream()
                .flatMap(w -> w.getWorkouts().stream())
                .flatMap(wo -> wo.getWorkoutExercises().stream())
                .flatMap(e -> e.getSets().stream())
                .map(UpdateWorkoutExerciseSetDTO::getVolumeMetric)
                .collect(Collectors.toSet());
        return volumeMetricRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(VolumeMetric::getId, vm -> vm));
    }

    private Map<Long, IntensityMetric> prefetchIntensityMetrics(ProgramUpdateDTO programDTO) {
        java.util.Set<Long> ids = programDTO.getWeeks().stream()
                .flatMap(w -> w.getWorkouts().stream())
                .flatMap(wo -> wo.getWorkoutExercises().stream())
                .flatMap(e -> e.getSets().stream())
                .map(UpdateWorkoutExerciseSetDTO::getIntensityMetric)
                .collect(Collectors.toSet());
        return intensityMetricRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(IntensityMetric::getId, im -> im));
    }

    private Map<Long, Exercise> prefetchExercises(ProgramCreateDTO programDTO) {
        java.util.Set<Long> ids = programDTO.getWeeks().stream()
                .flatMap(w -> w.getWorkouts().stream())
                .flatMap(wo -> wo.getWorkoutExercises().stream())
                .map(WorkoutExerciseDTO::getExercise)
                .collect(Collectors.toSet());
        return exerciseRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Exercise::getId, e -> e));
    }

    private Map<Long, VolumeMetric> prefetchVolumeMetrics(ProgramCreateDTO programDTO) {
        java.util.Set<Long> ids = programDTO.getWeeks().stream()
                .flatMap(w -> w.getWorkouts().stream())
                .flatMap(wo -> wo.getWorkoutExercises().stream())
                .flatMap(e -> e.getSets().stream())
                .map(CreateSetDTO::getVolumeMetric)
                .collect(Collectors.toSet());
        return volumeMetricRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(VolumeMetric::getId, vm -> vm));
    }

    private Map<Long, IntensityMetric> prefetchIntensityMetrics(ProgramCreateDTO programDTO) {
        java.util.Set<Long> ids = programDTO.getWeeks().stream()
                .flatMap(w -> w.getWorkouts().stream())
                .flatMap(wo -> wo.getWorkoutExercises().stream())
                .flatMap(e -> e.getSets().stream())
                .map(CreateSetDTO::getIntensityMetric)
                .collect(Collectors.toSet());
        return intensityMetricRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(IntensityMetric::getId, im -> im));
    }

    @Transactional
    public void deleteProgram(Long programId, String username) {
        if (!programRepository.existsById(programId)) {
            throw new ProgramNotFoundException("Program not found with ID: " + programId);
        }
        User creator = programRepository.findCreatorById(programId);
        if(!creator.getUsername().equals(username)) {
            throw new RuntimeException("You don't have permission to delete this program");
        }
        setRepository.bulkDeleteByProgramId(programId);
        workoutExerciseRepository.bulkDeleteByProgramId(programId);
        workoutRepository.bulkDeleteByProgramId(programId);
        weekRepository.bulkDeleteByProgramId(programId);
        programRepository.deleteById(programId);

    }

    public Page<ProgramOverviewDTO> getProgramsCreatedByMe(String userName, String title, Pageable pageable) {
        Page<Program> programsCreatedByMe;

        if (title == null || title.trim().isEmpty()) {
            programsCreatedByMe = programRepository.findByCreatorUsername(userName, pageable);
        } else {
            programsCreatedByMe = programRepository.findByCreatorUsernameAndTitleContainingIgnoreCase(
                    userName, title.trim(), pageable);
        }

        return programsCreatedByMe.map(ProgramOverviewDTO::fromEntity);
    }

}