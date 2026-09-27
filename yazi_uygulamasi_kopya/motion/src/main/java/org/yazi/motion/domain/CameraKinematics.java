package org.yazi.motion.domain;

import static org.yazi.motion.domain.DomainValidation.*;

public record CameraKinematics(
        ShotType shotType,
        CameraAngle cameraAngle,
        CameraMovement movement,
        MovementSpeed movementSpeed,
        double focalLengthMm) {

    public CameraKinematics {
        require(shotType, "shot_type");
        require(cameraAngle, "camera_angle");
        require(movement, "movement");
        require(movementSpeed, "movement_speed");
        requirePositive(focalLengthMm, "focal_length_mm");
    }
}
