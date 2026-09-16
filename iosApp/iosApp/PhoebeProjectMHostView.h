#pragma once

#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// GLES projectM → CVPixelBuffer/IOSurface → CALayer.contents (no CPU readback).
@interface PhoebeProjectMHostView : UIView

- (instancetype)initWithFrame:(CGRect)frame;
- (void)setPresetPath:(nullable NSString *)path;
- (void)setPresetData:(nullable NSString *)data;
- (void)setPresetLocked:(BOOL)locked;
- (void)setPlaying:(BOOL)playing;
- (void)setSuspended:(BOOL)suspended;
- (void)addPcmSamples:(const float *)samples count:(NSInteger)count channels:(NSInteger)channels;
- (BOOL)isNativeReady;

@end

NS_ASSUME_NONNULL_END
