import unittest

import recording as R


class Maths(unittest.TestCase):
    def test_a_turned_box_keeps_its_middle(self):
        # a 5 x 3 box turned a quarter is 3 x 5: its corner is where the turned box starts, the group's corner is where the unturned one would
        f = dict(x=10.0, y=2.0, z=20.0, rot=1, mirror=0)
        pos = R.lesson_pos(f, [5, 6, 3])
        self.assertEqual(pos, [10 + 3 / 2 - 5 / 2, 2.0, 20 + 5 / 2 - 3 / 2])
        # the unturned box at that corner has the same middle as the turned one
        self.assertEqual((pos[0] + 5 / 2, pos[2] + 3 / 2), (10 + 3 / 2, 20 + 5 / 2))

    def test_an_unturned_box_goes_where_the_origin_is(self):
        self.assertEqual(R.lesson_pos(dict(x=1.5, y=0.0, z=-2.0, rot=0, mirror=0), [5, 6, 5]), [1.5, 0.0, -2.0])
        self.assertEqual(R.lesson_pos(dict(x=1.5, y=0.0, z=-2.0, rot=2, mirror=0), [5, 6, 3]), [1.5, 0.0, -2.0])

    def test_the_game_mirrors_map_to_the_engines_one_and_a_turn(self):
        self.assertEqual(R.turn_and_mirror(0, 0), (0, 0))
        self.assertEqual(R.turn_and_mirror(1, 1), (1, 1))     # front-back: a flip across x
        self.assertEqual(R.turn_and_mirror(1, 2), (3, 1))     # left-right: a flip across x and a half turn
        self.assertEqual(R.turn_and_mirror(3, 2), (1, 1))

    def test_turns_do_not_jump(self):
        self.assertEqual(R.unwrap([0, 1, 2, 3, 0, 1]), [0, 1, 2, 3, 4, 5])
        self.assertEqual(R.unwrap([0, 3, 2]), [0, -1, -2])
        self.assertEqual(R.unwrap([2, 2, 2]), [2, 2, 2])

    def test_simplify_keeps_the_corners_and_drops_the_straight(self):
        keys = [(0, 0.0), (1, 1.0), (2, 2.0), (3, 2.0), (4, 2.0), (5, 3.0)]
        out = R.simplify(keys, 0.01)
        self.assertEqual(out, [(0, 0.0), (2, 2.0), (4, 2.0), (5, 3.0)])
        vec = [(0, [0, 0, 0]), (1, [1, 0, 0]), (2, [2, 0, 0]), (3, [2, 5, 0])]
        self.assertEqual(R.simplify(vec, 0.01), [(0, [0, 0, 0]), (2, [2, 0, 0]), (3, [2, 5, 0])])

    def test_a_gap_in_the_log_is_a_hold(self):
        keys = [(0.0, 1.0), (0.05, 2.0), (1.0, 2.0), (1.05, 3.0)]
        out = R.with_holds(keys)
        self.assertIn((0.95, 2.0), out)
        self.assertNotIn(0.0, [k[0] for k in out if k[1] == 9])
        # nothing is added where the frames follow each other
        self.assertEqual(len(R.with_holds([(0.0, 1.0), (0.05, 2.0), (0.1, 3.0)])), 3)

    def test_the_layer_window_of_a_take(self):
        rec = R.Recording({"marks": {}, "duration": 5.0, "aim": [], "input": [], "scroll": [], "ghosts": {"g": {"grid": {"size": [5, 6, 5], "layers": []}, "frames": [
            [0.0, 0, 2, 0, 0, 0, 1, 1, 0.6, -1, -1, 1], [1.0, 0, 2, 0, 0, 0, 1, 1, 0.6, 0, 0, 1], [2.0, 0, 2, 0, 0, 0, 1, 1, 0.6, 1, 1, 1], [3.0, 0, 2, 0, 0, 0, 1, 1, 0.6, 1, -1, 1],
            [4.0, 0, 2, 0, 0, 0, 1, 1, 0.6, 1, -1, 1]]}}})
        self.assertEqual(R.Take(rec, 2.0).window_keys("g", 6), [(2.0, (0, 5)), (3.0, (0, 0)), (4.0, (1, 1)), (5.0, (1, 5))])

    def test_a_recording_becomes_keys_on_the_lessons_clock(self):
        rec = R.Recording({"marks": {"a": 1.0, "done": 4.0}, "duration": 5.0, "aim": [], "input": [], "scroll": [], "ghosts": {"g": {
            "grid": {"size": [5, 6, 5], "layers": []},
            "frames": [[1.0, 0, 2, 0, 0, 0, 0, 1, 0.6, -1, -1, 1], [2.0, 4, 2, 0, 0, 0, 0, 1, 0.6, -1, -1, 1], [3.0, 4, 2, 0, 1, 0, 0, 1, 0.6, -1, -1, 1], [3.5, 4, 3, 0, 1, 2, 1, 1, 0.6, -1, -1, 1]]}}})
        take = R.Take(rec, 10.0)
        self.assertEqual(take.T("a"), 11.0)
        pos, turn, mirror = take.ghost_keys("g", ramp=0.4)
        self.assertEqual(pos[0], (11.0, [0, 2, 0]))
        self.assertEqual(pos[-1], (13.5, [4, 3, 0]))
        # the turn glides over 0.4 s from where it changed (t = 3 -> 13), and the half turn that the left-right mirror carries comes with it
        self.assertIn((13.0, 0), turn)
        self.assertIn((13.4, 1), turn)
        self.assertEqual(mirror[0], (11.0, 0))
        self.assertIn((13.5, 1), mirror)
        self.assertEqual(turn[-1], (13.9, 3.0))


if __name__ == "__main__":
    unittest.main()
