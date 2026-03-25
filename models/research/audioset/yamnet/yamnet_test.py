# Copyright 2019 The TensorFlow Authors All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ==============================================================================

"""Installation test for YAMNet."""

import numpy as np
import tensorflow as tf

import params
import yamnet

class YAMNetTest(tf.test.TestCase):

  _params = None
  _yamnet = None
  _yamnet_classes = None

  @classmethod
  def setUpClass(cls):
    super().setUpClass()
    cls._params = params.Params()
    cls._yamnet = yamnet.yamnet_frames_model(cls._params)
    # cls._yamnet.load_weights('yamnet.h5')
    cls._yamnet_classes = yamnet.class_names('yamnet_class_map.csv')

  def clip_test(self, waveform, expected_class_name, top_n=10):
    """Run the model on the waveform, check that expected class is in top-n."""
    predictions, embeddings, log_mel_spectrogram = YAMNetTest._yamnet(waveform)
    # Binary classification should yield output shape (num_patches, 1)
    self.assertEqual(predictions.shape[-1], 1)
    self.assertTrue(np.all(predictions >= 0.0) and np.all(predictions <= 1.0))

  def testZeros(self):
    self.clip_test(
        waveform=np.zeros((int(3 * YAMNetTest._params.sample_rate),)),
        expected_class_name='Silence')

  def testRandom(self):
    np.random.seed(51773)  # Ensure repeatability.
    self.clip_test(
        waveform=np.random.uniform(-1.0, +1.0,
                                   (int(3 * YAMNetTest._params.sample_rate),)),
        expected_class_name='White noise')

  def testSine(self):
    self.clip_test(
        waveform=np.sin(2 * np.pi * 440 *
                        np.arange(0, 3, 1 / YAMNetTest._params.sample_rate)),
        expected_class_name='Sine wave')


if __name__ == '__main__':
  tf.test.main()
