package com.app.fitness.fitnesprogramapp.models.workout;

import com.app.fitness.fitnesprogramapp.models.exercise.WorkoutExercise;
import com.app.fitness.fitnesprogramapp.models.week.Week;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;

@Entity
@Data
public class Workout {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "workout_seq")
    @SequenceGenerator(name = "workout_seq", sequenceName = "workout_seq", allocationSize = 50)
    private Long id;

    private String title;
    private String description;

    private Integer position;

    @OneToMany(
            mappedBy = "workout",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<WorkoutExercise> workoutExercises;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "week_id") // This column will be in the 'workout' table
    private Week week;


}
