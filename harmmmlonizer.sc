
var voiceChannelsGroup, voiceChannels, outputMixer, window, windowWidth, windowHeight, titleWidth, titleHeight, knobWidth, knobHeight, sliderWidth, sliderHeight, margin, voiceSectionWidth, voiceSectionYOffset, voiceSectionMargin, currentXPos, currentYPos, xOffset, masterTitle, pitchShifterTitle, button, buttonWidth, buttonHeight, knob, backgroundImage;

Server.killAll;
s.waitForBoot({

	/* ----- Environment settings ----- */
	(
		/* ----- Global settings ----- */
		~voiceNumber = 3;
		~windowSize = 0.075;
		~minDelayTime = s.options.blockSize/s.sampleRate;
		~maxDelayTime = 2.0;
		~fontName = "Arial Black";
		/* ----- Audio buses ----- */
		~inputAudioBus = Bus.audio(s, 1);
		~delayedVoiceBuses = Array.fill(~voiceNumber, {arg i; Bus.audio(s, 1)});
		~pitchShiftedVoiceBuses = Array.fill(~voiceNumber, {arg i; Bus.audio(s, 1)});
		/* ----- Control buses ----- */
		~pitchShifTimeDispersionControlBus = Bus.control(s, 1);
		~pitchShifPitchDispersionControlBus = Bus.control(s, 1);
		~pitchRatioControlBuses = Array.fill(~voiceNumber, {arg i; Bus.control(s, 1)});
		~stereoPanControlBuses = Array.fill(~voiceNumber, {arg i; Bus.control(s, 1)});
		~modeSelectionBuses = Array.fill(~voiceNumber, {arg i; Bus.control(s, 1)});
	);

	/* ----- Sound Input -----
	Reads the audio from the hardware input
	an writes it to inputAudioBus */
	(
		a = SynthDef.new(\soundIn, {
			Out.ar(
				bus: ~inputAudioBus,
				channelsArray: SoundIn.ar(0);
			);
		}).add;
	);

	/* ----- Pitch Detection - (Future improvement) -----
	Detects the pitch of the input signal and automatically sets the
	values for the voices's pitch ratios. These will be computed
	accordingly to the specified tonality and scale mode. */
	/*(
		SynthDef.new(\pitchDetection, {
			var input, freq, hasFreq;
			input = In.ar(~inputAudioBus, 1);
			//# freq, hasFreq = Tartini.kr(in: input, threshold: 0.93, n: 2048, k: 0, overlap: 1024, smallCutoff: 0.5);
			# freq, hasFreq = Pitch.kr(
				input: input,
				initFreq: 440.0,
				minFreq: 60.0,
				maxFreq: 4000.0,
				execFreq: 100.0,
				maxBinsPerOctave: 16,
				median: 1,
				peakThreshold: 0.5,
				downSample: 1,
				clar: 0
			);
			// Calculate pitch ratios based on freq and knobs values and write them to buses
		}).add
	);*/

	/* ----- Pitch Shifter -----
	Pitch shift the input signal based on the selected mode. */
	(
		b = SynthDef.new(\pitchShifter, { arg channelIndex, gain = 0.0;

			var input, delayedSignal, pitchShiftInput, isPitchShiftFeedbackMode, pitchRatio, pitchDispersion, timeDispersion, pitchShiftedSignal, isCrossFeedback, selectedMode;

			input = In.ar(~inputAudioBus, 1);
			delayedSignal = InFeedback.ar(Select.kr(channelIndex, ~delayedVoiceBuses), 1);
			selectedMode = In.kr(Select.kr(channelIndex, ~modeSelectionBuses), 1);
			pitchShiftInput = Select.ar(selectedMode, [input, Mix.new([input, delayedSignal]), input]);
			pitchDispersion = In.kr(~pitchShifPitchDispersionControlBus, 1);
			timeDispersion = In.kr(~pitchShifTimeDispersionControlBus, 1);
			pitchRatio = In.kr(Select.kr(channelIndex, ~pitchRatioControlBuses), 1);
			pitchShiftedSignal = PitchShift.ar(
				in: pitchShiftInput,
				windowSize: ~windowSize,
				pitchRatio: (2.pow(1/12)).pow(pitchRatio),
				pitchDispersion: pitchDispersion,
				timeDispersion: ~windowSize*timeDispersion,
				mul: gain
			);

			Out.ar(Select.kr(channelIndex, ~pitchShiftedVoiceBuses), pitchShiftedSignal);
		}).add;
	);

	/* ----- Feedback Delay Line -----
	A feedback delay line that uses the FbNode class of the Feedback Quark: the input feedback signal
	to the node changes accordingly to the selected mode. */
	(
		c = SynthDef.new(\feedbackDelayLine, { arg channelIndex, delayTime = ~minDelayTime, feedbackAmount = 0.0;

			var input, feedbackNode, delayedSignal, feedbackSignal, pitchShiftedSignal, selectedMode, channelFeedbackBus;

			input = In.ar(~inputAudioBus, 1);
			feedbackNode = FbNode(numChannels: 1, maxdelaytime: ~maxDelayTime, interpolation: 2);
			delayedSignal = feedbackNode.delay(delayTime);
			pitchShiftedSignal = In.ar(Select.kr(channelIndex, ~pitchShiftedVoiceBuses), 1);

			selectedMode = In.kr(Select.kr(channelIndex, ~modeSelectionBuses), 1);

			delayedSignal = Select.ar(selectedMode, [
				delayedSignal,
				delayedSignal,
				Mix.new([delayedSignal] ++ ~pitchShiftedVoiceBuses.collect({
					arg pitchShiftedVoiceBus, i;
					if (pitchShiftedVoiceBus.index != channelIndex,
						{ feedbackAmount*InFeedback.ar(pitchShiftedVoiceBus, 1) },
						{ 0.0 }
					);
				}));
			]);

			feedbackSignal = Select.ar(selectedMode, [
				feedbackAmount*Mix.new([pitchShiftedSignal, delayedSignal]),
				Mix.new([feedbackAmount*pitchShiftedSignal]),
				feedbackAmount*Mix.new([pitchShiftedSignal, delayedSignal]),
			]);

			feedbackNode.write(feedbackSignal);
			Out.ar(Select.kr(channelIndex, ~delayedVoiceBuses), delayedSignal);
		}).add;
	);

	/* ----- Mixer -----
	Mixes the mono input and the pitch shifted voices to a stereo ouput. */
	(
		d = SynthDef.new(\mixer, { arg master = 1, wet = 0.5;
			var stereoInput, stereoOutput, voiceStereoSignals, selectedMode;

			stereoInput = Pan2.ar((1 - wet) * In.ar(~inputAudioBus, 1), 0.0);
			voiceStereoSignals = ~pitchShiftedVoiceBuses.collect({
				arg pitchShiftedVoiceBus, i;
				var pitchShiftedVoice, delayedSignal, stereoPan, voiceOutput;
				stereoPan = In.kr(~stereoPanControlBuses[i], 1);
				pitchShiftedVoice = In.ar(pitchShiftedVoiceBus, 1);
				delayedSignal = In.ar(~delayedVoiceBuses[i], 1);
				selectedMode = In.kr(Select.kr(i, ~modeSelectionBuses), 1);
				voiceOutput = Select.ar(selectedMode, [
					Mix.new([pitchShiftedVoice, delayedSignal]),
					pitchShiftedVoice,
					Mix.new([pitchShiftedVoice, delayedSignal])
				]);
				Pan2.ar(wet * voiceOutput, stereoPan);
			});

			stereoOutput = Mix.new(stereoInput ++ voiceStereoSignals);
			Out.ar(0, master * stereoOutput);
		}).add;
	);

	/* ----- Synths and GUI ----- */
	(
		AppClock.sched(0.05, {
			/* ----- Synths ----- */
			x = Synth(\soundIn);
			voiceChannelsGroup = ParGroup.after(x);
			voiceChannels = Array.fill(~voiceNumber, {
				arg i;
				var pitchShifter, feedbackDelayLine;
				pitchShifter = Synth.head(voiceChannelsGroup, \pitchShifter, [\channelIndex, i]);
				feedbackDelayLine = Synth.after(pitchShifter, \feedbackDelayLine, [\channelIndex, i]);
				[pitchShifter, feedbackDelayLine];
			});
			outputMixer = Synth.after(voiceChannelsGroup, \mixer);

			/* ----- GUI ----- */
			Window.closeAll;
			windowWidth = 1225;
			windowHeight = 800;
			titleWidth = 200;
			titleHeight = 70;
			knobWidth = 125;
			knobHeight = knobWidth;
			sliderWidth = 300;
			sliderHeight = 50;
			buttonWidth = 120;
			buttonHeight = 40;
			margin = 5@5;

			window = Window(
				name: "HarMMMLonizer",
				bounds: Rect(100, 100, windowWidth, windowHeight),
				resizable: false,
				border: true,
				scroll: false
			);
			try {
				backgroundImage = Image.open(thisProcess.nowExecutingPath.dirname +/+ "assets/images/background.png");
			} {
				backgroundImage = Image.color(windowWidth, windowHeight, Color.new255(140, 175, 189));
			};
			window.view.backgroundImage_(backgroundImage);

			/* ----- Master Section ----- */
			currentYPos = 50;
			/* ----- Input Meter ----- */
			currentXPos = 175;
			ServerMeterView.new(
				aserver: s,
				parent: window,
				leftUp: currentXPos@currentYPos,
				numIns: 1,
				numOuts: 0
			);
			/* ----- Title ----- */
			currentXPos = (730 - titleWidth)/2;
			masterTitle = StaticText(
				parent: window,
				bounds: Rect(currentXPos, currentYPos, titleWidth, titleHeight)
			);
			masterTitle.string = "Master";
			masterTitle.font = Font("~fontName", 30);
			masterTitle.align = \center;
			currentYPos = currentYPos + titleHeight;
			/* ----- Master Gain Knob ----- */
			currentXPos = 730/2 - knobWidth;
			knob = EZKnob(
				parent: window,
				bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
				label: "Gain",
				controlSpec: ControlSpec.new(minval: 0.0, maxval: 2.0, warp: \lin),
				action: {arg thisKnob; outputMixer.set(\master, thisKnob.value)},
				initVal: 1.0,
				initAction: false,
				labelWidth: 60,
				// knobSize: an instance of Point,
				unitWidth: 0,
				labelHeight: 20,
				layout: \vert2,
				// gap: an instance of Point,
				margin: margin
			);
			knob.font = Font(~fontName, 11);
			/* ----- Dry/Wet Knob ----- */
			currentXPos = currentXPos + knobWidth;
			knob = EZKnob(
				parent: window,
				bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
				label: "Dry/Wet",
				controlSpec: ControlSpec.new(minval: 0.0, maxval: 1.0, warp: \lin),
				action: {arg thisKnob; outputMixer.set(\wet, thisKnob.value)},
				initVal: 0.5,
				initAction: false,
				labelWidth: 60,
				// knobSize: an instance of Point,
				unitWidth: 0,
				labelHeight: 20,
				layout: \vert2,
				// gap: an instance of Point,
				margin: margin
			);
			knob.font = Font(~fontName, 11);
			/* ----- Output Meter ----- */
			currentXPos = currentXPos + knobWidth;
			currentYPos = 50;
			ServerMeterView.new(
				aserver: s,
				parent: window,
				leftUp: currentXPos@currentYPos,
				numIns: 0,
				numOuts: 2
			);

			/* ----- Pitch Shifter Section ----- */
			currentXPos = (1690 - titleWidth)/2;
			currentYPos = 50;
			pitchShifterTitle = StaticText(
				parent: window,
				bounds: Rect(currentXPos, currentYPos, titleWidth, titleHeight)
			);
			pitchShifterTitle.string = "Pitch Shifter";
			pitchShifterTitle.font = Font("~fontName", 30);
			pitchShifterTitle.align = \center;
			currentYPos = currentYPos + titleHeight;
			currentXPos = 1425/2 - knobWidth;
			/* ----- Pitch Dispersion Knob ----- */
			currentXPos = currentXPos + knobWidth;
			knob = EZKnob(
				parent: window,
				bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
				label: "Pitch Dispersion",
				controlSpec: ControlSpec.new(minval: 0.0, maxval: 0.5, warp: \lin, step: 0.0001),
				action: {arg thisKnob; ~pitchShifPitchDispersionControlBus.set(thisKnob.value)},
				initVal: 0.0001,
				initAction: true,
				labelWidth: 60,
				// knobSize: an instance of Point,
				unitWidth: 0,
				labelHeight: 20,
				layout: \vert2,
				// gap: an instance of Point,
				margin: margin
			);
			knob.font = Font(~fontName, 11);
			/* ----- Time Dispersion Knob ----- */
			currentXPos = currentXPos + knobWidth;
			knob = EZKnob(
				parent: window,
				bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
				label: "Time Dispersion",
				controlSpec: ControlSpec.new(minval: 0.0, maxval: 1.0, warp: \lin, step: 0.01),
				action: {arg thisKnob; ~pitchShifTimeDispersionControlBus.set(thisKnob.value)},
				initVal: 1.0,
				initAction: true,
				labelWidth: 60,
				// knobSize: an instance of Point,
				unitWidth: 0,
				labelHeight: 20,
				layout: \vert2,
				// gap: an instance of Point,
				margin: margin
			);
			knob.font = Font(~fontName, 11);

			/* ----- Voice Channels Section ----- */
			voiceChannels.do({ arg voiceChannel, index;

				var title, pitchShifter, feedbackDelayLine, pitchfbModeCheckbox, crossfbModeCheckbox;
				xOffset = 55 + (400*index);

				pitchShifter = voiceChannel[0];
				feedbackDelayLine = voiceChannel[1];

				/* ----- Title ----- */
				currentXPos = xOffset + ((sliderWidth - titleWidth)/2);
				currentYPos = 280;
				title = StaticText(
					parent: window,
					bounds: Rect(currentXPos, currentYPos, titleWidth, titleHeight)
				);
				title.string = "Voice " ++ (index + 1);
				title.font = Font("~fontName", 30);
				title.align = \center;

				/* ----- First Line ----- */
				currentYPos = currentYPos + titleHeight;
				/* ----- Amount Knob ----- */
				currentXPos = xOffset + (sliderWidth/2 - knobWidth);
				knob = EZKnob(
					parent: window,
					bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
					label: "Gain",
					controlSpec: ControlSpec.new(minval: 0.0, maxval: 2.0, warp: \lin),
					action: {arg thisKnob; pitchShifter.set(\gain, thisKnob.value)},
					initVal: 0.0,
					initAction: false,
					labelWidth: 60,
					// knobSize: an instance of Point,
					unitWidth: 0,
					labelHeight: 20,
					layout: \vert2,
					// gap: an instance of Point,
					margin: margin
				);
				knob.font = Font(~fontName, 11);
				/* ----- Stereo Pan Knob ----- */
				currentXPos = currentXPos + knobWidth;
				knob = EZKnob(
					parent: window,
					bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
					label: "Pan",
					controlSpec: ControlSpec.new(minval: -1.0, maxval: 1.0, warp: \lin),
					action: {arg thisKnob; ~stereoPanControlBuses[index].set(thisKnob.value)},
					initVal: 0.0,
					initAction: true,
					labelWidth: 60,
					// knobSize: an instance of Point,
					unitWidth: 0,
					labelHeight: 20,
					layout: \vert2,
					// gap: an instance of Point,
					margin: margin
				);
				knob.font = Font(~fontName, 11);

				/* ----- Third Line ----- */
				currentYPos = currentYPos + knobHeight + 30;
				/* ----- Pitch Ratio Slider ----- */
				currentXPos = xOffset;
				knob = EZSlider(
					parent: window,
					bounds: Rect(currentXPos, currentYPos, sliderWidth, sliderHeight),
					label: "Pitch Ratio",
					controlSpec: ControlSpec(minval: -24, maxval: 24, warp: \lin, step: 1, units: \st),
					action: {arg thisSlider; ~pitchRatioControlBuses[index].set(thisSlider.value)},
					initVal: 0,
					initAction: true,
					labelWidth: 60,
					numberWidth: 60,
					// knobSize: an instance of Point,
					unitWidth: 30,
					labelHeight: 20,
					layout: \line2,
					// gap: an instance of Point,
					margin: margin
				);
				knob.font = Font(~fontName, 11);

				/* ----- Fourth Line ----- */
				currentYPos = currentYPos + sliderHeight + 30;
				/* ----- Delay Time Knob ----- */
				currentXPos = xOffset  + (sliderWidth/2 - knobWidth);
				knob = EZKnob(
					parent: window,
					bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
					label: "Delay Time",
					controlSpec: ControlSpec.new(minval: ~minDelayTime, maxval: ~maxDelayTime, warp: \lin),
					action: {arg thisKnob; feedbackDelayLine.set(\delayTime, thisKnob.value)},
					initVal: ~minDelayTime,
					initAction: false,
					labelWidth: 60,
					// knobSize: an instance of Point,
					unitWidth: 0,
					labelHeight: 20,
					layout: \vert2,
					// gap: an instance of Point,
					margin: margin
				);
				knob.font = Font(~fontName, 11);
				/* ----- Feedback Amount Knob ----- */
				currentXPos = currentXPos + knobWidth;
				knob = EZKnob(
					parent: window,
					bounds: Rect(currentXPos, currentYPos, knobWidth, knobHeight),
					label: "Feedback",
					controlSpec: ControlSpec.new(minval: 0.0, maxval: 2.0, warp: \lin),
					action: {arg thisKnob; feedbackDelayLine.set(\feedbackAmount, thisKnob.value)},
					initVal: 0.0,
					initAction: false,
					labelWidth: 60,
					// knobSize: an instance of Point,
					unitWidth: 0,
					labelHeight: 20,
					layout: \vert2,
					// gap: an instance of Point,
					margin: margin
				);
				knob.font = Font(~fontName, 11);

				/* ----- Fifth Line ----- */
				/* ----- Delay Mode Selection Button ----- */
				currentYPos = currentYPos + knobHeight + 30;
				button = Button.new(
					parent: window,
					bounds: Rect(currentXPos - (buttonWidth/2) - 2.5, currentYPos, buttonWidth, buttonHeight));
				button.states = [["Normal", Color.black], ["Pitch Feedback", Color.black], ["Cross Feedback", Color.black]];
				button.action = ({ arg me;
					~modeSelectionBuses[index].set(me.value);
				});
				button.font = Font(~fontName, 11);

			});

			window.front;
			window.onClose_({x.free; voiceChannelsGroup.freeAll; outputMixer.free;});
		});
	);
});

/* ----- UGen Graphs -----
Draws the SynthDefs UGen Graphs with the sc3-dot Quark.
Graphs are saved in SVG format in the assets/graphs directory. */
/*(
Dot.directory = thisProcess.nowExecutingPath.dirname +/+ "assets/graphs";
Dot.fontSize = 16; /* default = 10 */
Dot.useSplines = true; /* default = false */
Dot.drawInputName = true; /* default = false */
Dot.useTables = true; /* default = true */
Dot.renderMode = 'svg'; // run dot to generate a .pdf file and view that

a.draw; b.draw; c.draw; d.draw;
);*/

